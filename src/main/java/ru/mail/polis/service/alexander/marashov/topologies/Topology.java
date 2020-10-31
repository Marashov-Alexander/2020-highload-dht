package ru.mail.polis.service.alexander.marashov.topologies;

import one.nio.net.ConnectionString;

import one.nio.http.HttpClient;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public interface Topology<N> {
    boolean isLocal(final N node);

    N primaryFor(final ByteBuffer key);

    N[] primariesFor(final ByteBuffer key, final int count);

    N[] all();

    int size();

    default int getQuorumCount() {
        return size() / 2 + 1;
    }

    default Map<N, HttpClient> clientsToOtherNodes(final int proxyTimeoutValue) {
        final HashMap<N, HttpClient> clientsToOtherNodes = new HashMap<>();
        for (final N node : all()) {
            if (isLocal(node)) {
                continue;
            }
            final HttpClient httpClient = new HttpClient(new ConnectionString(node + "?timeout=" + proxyTimeoutValue));
            if (clientsToOtherNodes.put(node, httpClient) != null) {
                throw new IllegalStateException("Duplicate node");
            }
        }
        return clientsToOtherNodes;
    }
}
