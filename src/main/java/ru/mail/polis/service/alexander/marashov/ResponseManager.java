package ru.mail.polis.service.alexander.marashov;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.alexander.marashov.Value;
import ru.mail.polis.service.alexander.marashov.analyzers.FutureAnalyzer;
import ru.mail.polis.service.alexander.marashov.analyzers.ValuesAnalyzer;
import ru.mail.polis.service.alexander.marashov.bodyhandlers.BodyHandlerGet;
import ru.mail.polis.service.alexander.marashov.topologies.Topology;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static ru.mail.polis.service.alexander.marashov.ServiceImpl.EXPIRES_HEADER;
import static ru.mail.polis.service.alexander.marashov.ServiceImpl.PROXY_HEADER;
import static ru.mail.polis.service.alexander.marashov.ServiceImpl.TIMESTAMP_HEADER_NAME;

public class ResponseManager {

    private final Topology<String> topology;
    private final HttpClient httpClient;
    private final DaoManager daoManager;

    private final Duration timeout;

    /**
     * Object for managing responses.
     * @param dao - DAO instance.
     * @param topology - object with cluster info.
     * @param proxyTimeoutValue - proxy timeout value in milliseconds.
     * @param proxyExecutor - executorService instance where proxy requests should be executed.
     */
    public ResponseManager(
            final DAO dao,
            final ExecutorService daoExecutor,
            final ExecutorService proxyExecutor,
            final Topology<String> topology,
            final int proxyTimeoutValue
    ) {
        this.daoManager = new DaoManager(dao, daoExecutor);
        this.topology = topology;
        this.timeout = Duration.ofMillis(proxyTimeoutValue);
        this.httpClient = HttpClient.newBuilder()
                .executor(proxyExecutor)
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Executes a get request to the DAO.
     * @param validParams - validated parameters object.
     * @param request - original user's request.
     * @return response - the final result of the completed request.
     */
    public CompletableFuture<Response> get(final ValidatedParameters validParams, final Request request) {

        final String proxyHeader = request.getHeader(PROXY_HEADER);
        if (proxyHeader != null) {
            return daoManager.rowGet(validParams.key).thenApply(
                    value -> {
                        if (value == null) {
                            return new Response(Response.NOT_FOUND, Response.EMPTY);
                        } else if (value.isTombstone()) {
                            final Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                            response.addHeader(TIMESTAMP_HEADER_NAME + ": " + value.getTimestamp());
                            return response;
                        } else {
                            final Response response = new Response(Response.OK, value.getData().array());
                            response.addHeader(TIMESTAMP_HEADER_NAME + ": " + value.getTimestamp());
                            return response;
                        }
                    }
            );
        }

        final String[] primaries = topology.primariesFor(validParams.key, validParams.from);

        final List<CompletableFuture<Value>> list = new ArrayList<>(primaries.length);
        for (final String node : primaries) {
            if (topology.isLocal(node)) {
                list.add(daoManager.rowGet(validParams.key));
            } else {
                final HttpRequest httpRequest = requestForReplicaBuilder(node, validParams.rowKey, timeout)
                        .GET()
                        .header(PROXY_HEADER, "true")
                        .build();
                final CompletableFuture<Value> future = httpClient.sendAsync(httpRequest, BodyHandlerGet.INSTANCE)
                        .thenApply(HttpResponse::body);
                list.add(future);
            }
        }
        return FutureAnalyzer.atLeastAsync(list, validParams.ack)
                .thenApply(ValuesAnalyzer::analyze);
    }

    private static HttpRequest.Builder requestForReplicaBuilder(
            final String node,
            final String id,
            final Duration timeout
    ) {
        final String uri = node + "/v0/entity?id=" + id;
        try {
            return HttpRequest.newBuilder()
                    .timeout(timeout)
                    .uri(new URI(uri));
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URI: " + uri, e);
        }
    }

    /**
     * Executes a put request to the DAO.
     * @param validParams - validated parameters object.
     * @param value - byte buffer with value to put.
     * @param request - original user's request.
     * @return response - the final result of the completed request.
     */
    public CompletableFuture<Response> put(
            final ValidatedParameters validParams,
            final ByteBuffer value,
            final byte[] rowValue,
            final Request request
    ) {
        final String proxyHeader = request.getHeader(PROXY_HEADER);
        final String expiresHeader = request.getHeader(EXPIRES_HEADER);

        long expiresTimestamp;
        try {
            expiresTimestamp = Long.parseLong(expiresHeader);
        } catch (final NumberFormatException e) {
            expiresTimestamp = Value.NEVER_EXPIRES;
        }

        if (proxyHeader != null) {
            return daoManager.put(validParams.key, value, expiresTimestamp);
        }
        final String[] primaries = topology.primariesFor(validParams.key, validParams.from);

        final List<CompletableFuture<Response>> list = new ArrayList<>(primaries.length);
        for (final String node : primaries) {
            if (topology.isLocal(node)) {
                list.add(daoManager.put(validParams.key, value, expiresTimestamp));
            } else {
                final HttpRequest httpRequest =
                        requestForReplicaBuilder(node, validParams.rowKey, timeout)
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(rowValue))
                                .header(PROXY_HEADER, "true")
                                .build();
                final CompletableFuture<Response> future =
                        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding())
                                .thenApply(voidHttpResponse -> new Response(Response.CREATED, Response.EMPTY));
                list.add(future);
            }
        }
        return FutureAnalyzer.atLeastAsync(list, validParams.ack)
                .thenApply(
                        responses -> responses.size() >= validParams.ack
                            ? new Response(Response.CREATED, Response.EMPTY)
                            : new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY)
                );
    }

    /**
     * Executes a delete request to the DAO.
     * @param validParams - validated parameters object.
     * @param request - original user's request.
     * @return response - the final result of the completed request.
     */
    public CompletableFuture<Response> delete(final ValidatedParameters validParams, final Request request) {
        final String proxyHeader = request.getHeader(PROXY_HEADER);
        if (proxyHeader != null) {
            return daoManager.delete(validParams.key);
        }

        final String[] primaries = topology.primariesFor(validParams.key, validParams.from);

        final List<CompletableFuture<Response>> list = new ArrayList<>(primaries.length);
        for (final String node : primaries) {
            if (topology.isLocal(node)) {
                list.add(daoManager.delete(validParams.key));
            } else {
                final HttpRequest httpRequest =
                        requestForReplicaBuilder(node, validParams.rowKey, timeout)
                                .DELETE()
                                .header(PROXY_HEADER, "true")
                                .build();
                final CompletableFuture<Response> future =
                        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding())
                                .thenApply(voidHttpResponse -> new Response(Response.ACCEPTED, Response.EMPTY));
                list.add(future);
            }
        }
        return FutureAnalyzer.atLeastAsync(list, validParams.ack)
                .thenApply(
                        responses -> responses.size() >= validParams.ack
                                ? new Response(Response.ACCEPTED, Response.EMPTY)
                                : new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY)
                );
    }

    public CompletableFuture<Iterator<Record>> iterator(final ByteBuffer keyFrom, final ByteBuffer keyTo) {
        return daoManager.iterator(keyFrom, keyTo);
    }
}
