package ru.mail.polis.service.alexander.marashov.analyzers;

import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.alexander.marashov.Value;
import ru.mail.polis.service.alexander.marashov.ValueSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ResponseAnalyzerGet extends ResponseAnalyzer<Value> {

    private static final Logger log = LoggerFactory.getLogger(ResponseAnalyzerGet.class);
    private final Map<Value, Integer> answersMap;
    private Value newestTombstone;

    /**
     * Response analyzer that accumulates responses from DAO's GET methods and analyzes them.
     * @param neededReplicasCount - how many replicas is required.
     * @param totalReplicasCount - how many replicas is expected.
     */
    public ResponseAnalyzerGet(final int neededReplicasCount, final int totalReplicasCount) {
        super(neededReplicasCount, totalReplicasCount);
        this.answersMap = new HashMap<>();
        this.newestTombstone = null;
    }

    @Override
    protected boolean hasEnoughAnswers() {
        return this.answeredCount >= this.neededReplicasCount
                || super.hasEnoughAnswers();
    }

    @Override
    protected void privateAccept(final Response response) {
        if (response == null) {
            failedCount++;
            return;
        }
        if (response.getStatus() != 200) {
            answeredCount++;
            return;
        }
        final Value value;
        try {
            value = ValueSerializer.deserialize(response.getBody());
        } catch (final ClassNotFoundException | IOException e) {
            log.error("Value deserialize error", e);
            return;
        }
        privateAccept(value);
    }

    @Override
    protected void privateAccept(final Value value) {
        answeredCount++;
        if (value != null) {
            if (value.isTombstone()) {
                if (newestTombstone == null || value.compareTo(newestTombstone) < 0) {
                    newestTombstone = value;
                }
                return;
            }
            answersMap.compute(value, (v, oldCount) -> {
                if (oldCount == null) {
                    return 1;
                }
                return oldCount + 1;
            });
        }
    }

    @Override
    protected Response privateGetResult() {
        if (this.answeredCount < this.neededReplicasCount) {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }
        if (answersMap.isEmpty()) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        Value correctValue = null;
        int maxCount = 0;
        for (final Map.Entry<Value, Integer> answerEntry : answersMap.entrySet()) {
            final Value value = answerEntry.getKey();
            assert !value.isTombstone();
            final int count = answerEntry.getValue();

            if (correctValue == null) {
                maxCount = count;
                correctValue = value;
            } else {
                final boolean comparisonIfCountEquals = count == maxCount && value.compareTo(correctValue) < 0;
                if (comparisonIfCountEquals || count > maxCount) {
                    correctValue = value;
                    maxCount = count;
                }
            }
        }

        if (newestTombstone != null && newestTombstone.compareTo(correctValue) < 0) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return new Response(Response.OK, correctValue.getData().array());
    }
}
