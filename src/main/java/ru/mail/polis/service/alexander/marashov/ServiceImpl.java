package ru.mail.polis.service.alexander.marashov;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;
import ru.mail.polis.service.alexander.marashov.topologies.Topology;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.mail.polis.service.alexander.marashov.ValidatedParameters.validateParameters;

public class ServiceImpl extends HttpServer implements Service {

    private static final Logger log = LoggerFactory.getLogger(ServiceImpl.class);

    public static final String PROXY_HEADER = "Proxy_Header";
    public static final String EXPIRES_HEADER_NAME = "Expires";
    public static final String TIMESTAMP_HEADER_NAME = "Timestamp_Header";
    public static final String FUTURE_CANCELED_ERROR = "Who canceled my future?!";

    private final ExecutorService executorService;
    private final ResponseManager responseManager;

    private final int nodesCount;
    private final int defaultAck;
    private final int defaultFrom;

    /**
     * Implementation of a persistent storage with HTTP API.
     *
     * @author Marashov Alexander
     */
    public ServiceImpl(
            final int port,
            @NotNull final DAO dao,
            final Topology<String> topology,
            final int workersCount,
            final int queueSize,
            final int proxyTimeoutValue
    ) throws IOException {
        super(configFrom(port));
        this.executorService = new ThreadPoolExecutor(
                workersCount,
                workersCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactoryBuilder()
                        .setNameFormat("worker-%d")
                        .setUncaughtExceptionHandler((t, e) -> log.error("{}: uncaught exception", t, e))
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        final ThreadPoolExecutor proxyExecutor = new ThreadPoolExecutor(
                workersCount,
                workersCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactoryBuilder()
                        .setNameFormat("proxy-worker-%d")
                        .setUncaughtExceptionHandler((t, e) -> log.error("{}: uncaught exception", t, e))
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.responseManager = new ResponseManager(
                dao,
                executorService,
                proxyExecutor,
                topology,
                proxyTimeoutValue
        );
        this.defaultAck = topology.getQuorumCount();
        this.defaultFrom = topology.size();
        this.nodesCount = topology.size();
    }

    /**
     * Static function for creating an {@link HttpServerConfig} instance.
     *
     * @param port port on which the {@link HttpServer} should listen.
     * @return {@link HttpServerConfig} for {@link HttpServer} with specified port.
     */
    @NotNull
    private static HttpServerConfig configFrom(final int port) {
        final AcceptorConfig ac = new AcceptorConfig();
        ac.port = port;

        final HttpServerConfig config = new HttpServerConfig();
        config.acceptors = new AcceptorConfig[]{ac};
        return config;
    }

    private void executeOrSendError(final HttpSession httpSession, final Runnable runnable) {
        try {
            executorService.execute(runnable);
        } catch (final RejectedExecutionException e) {
            log.error("Request rejected", e);
            trySendAnswer(httpSession, new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private void trySendAnswer(final HttpSession httpSession, final Response response) {
        try {
            httpSession.sendResponse(response);
        } catch (final IOException ioException) {
            log.error("Sending response error", ioException);
        }
    }

    private void respond(final HttpSession httpSession, final CompletableFuture<Response> futureResponse) {
        final boolean canceled = futureResponse.whenComplete((response, error) -> {
            if (error == null) {
                trySendAnswer(httpSession, response);
            } else {
                log.error("Future returned error", error);
                if (error.getCause() instanceof IllegalStateException) {
                    trySendAnswer(httpSession, new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                } else {
                    trySendAnswer(httpSession, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                }
            }
        }).isCancelled();
        if (canceled) {
            throw new IllegalStateException(FUTURE_CANCELED_ERROR);
        }
    }

    private void respondMultiple(final HttpSession httpSession, final CompletableFuture<Iterator<Record>> future) {
        final boolean canceled = future.whenComplete((recordIterator, error) -> {
            if (error == null) {
                try {
                    ((CustomHttpSession) httpSession).sendRecords(recordIterator);
                } catch (final IOException ioException) {
                    log.error("Error sending response", ioException);
                    trySendAnswer(httpSession, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                }
            } else {
                log.error("Future returned error", error);
                trySendAnswer(httpSession, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        }).isCancelled();
        if (canceled) {
            throw new RuntimeException(FUTURE_CANCELED_ERROR);
        }
    }

    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        final Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public HttpSession createSession(final Socket socket) throws RejectedSessionException {
        return new CustomHttpSession(socket, this);
    }

    /**
     * Http method handler for checking server's reachability.
     *
     * @return {@link Response} with status {@code 200} if the server is available.
     */
    @Path("/v0/status")
    public Response handleStatus() {
        return new Response(Response.OK, Response.EMPTY);
    }

    /**
     * Http method handler for managing the dao.
     *
     * @param id - dao record key.
     * @param replicas - replicas parameter, has ack/from format.
     */
    @Path("/v0/entity")
    public void handleEntity(
            final HttpSession httpSession,
            final Request request,
            @Param(value = "id", required = true) final String id,
            @Param(value = "replicas") final String replicas
    ) {
        executeOrSendError(
                httpSession,
                () -> {
                    final ValidatedParameters validParams;
                    try {
                        validParams = validateParameters(id, replicas, defaultAck, defaultFrom, nodesCount);
                    } catch (final IllegalArgumentException e) {
                        trySendAnswer(httpSession, new Response(Response.BAD_REQUEST, Response.EMPTY));
                        return;
                    }
                    switch (request.getMethod()) {
                        case Request.METHOD_GET: {
                            respond(httpSession, responseManager.get(validParams, request));
                            break;
                        }
                        case Request.METHOD_PUT: {
                            final ByteBuffer value = ByteBuffer.wrap(request.getBody());
                            respond(httpSession, responseManager.put(validParams, value, request.getBody(), request));
                            break;
                        }
                        case Request.METHOD_DELETE: {
                            respond(httpSession, responseManager.delete(validParams, request));
                            break;
                        }
                        default: {
                            trySendAnswer(httpSession, new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                            break;
                        }
                    }
                }
        );
    }

    /**
     * HTTP method handler for getting key-values pairs from the DAO storage.
     *
     * @param start - key to start the search.
     * @param end - key where the search will stop.
     */
    @Path("/v0/entities")
    @RequestMethod(Request.METHOD_GET)
    public void handleEntitiesGet(
            final HttpSession httpSession,
            @Param(value = "start", required = true) final String start,
            @Param(value = "end") final String end
    ) {
        executeOrSendError(
                httpSession,
                () -> {
                    if (start.isEmpty() || (end != null && end.isEmpty())) {
                        trySendAnswer(httpSession, new Response(Response.BAD_REQUEST, Response.EMPTY));
                        return;
                    }

                    final byte[] bytesFrom = start.getBytes(StandardCharsets.UTF_8);
                    final ByteBuffer keyFrom = ByteBuffer.wrap(bytesFrom);

                    final ByteBuffer keyTo;
                    if (end == null) {
                        keyTo = null;
                    } else {
                        final byte[] bytesTo = end.getBytes(StandardCharsets.UTF_8);
                        keyTo = ByteBuffer.wrap(bytesTo);
                    }

                    final CompletableFuture<Iterator<Record>> iteratorFuture = responseManager.iterator(keyFrom, keyTo);
                    respondMultiple(httpSession, iteratorFuture);
                }
        );
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executorService.shutdown();
        try {
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            log.error("Waiting for a stop is interrupted");
            Thread.currentThread().interrupt();
        }
        responseManager.clear();
    }
}
