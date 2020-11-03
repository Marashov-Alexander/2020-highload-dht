package ru.mail.polis.service.alexander.marashov.analyzers;

import one.nio.http.Response;
import ru.mail.polis.dao.alexander.marashov.Value;
import ru.mail.polis.service.alexander.marashov.ServiceImpl;

import java.util.Collection;

public class ValuesAnalyzer {

    private ValuesAnalyzer() {

    }

    public static Response analyze(final Collection<Value> collection, final int ack) {
        if (collection.size() < ack) {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }

        Value correctValue = null;
        for (final Value value : collection) {
            ServiceImpl.log.debug("{}", value);
            if (correctValue == null) {
                correctValue = value;
            } else if (value.compareTo(correctValue) < 0) {
                correctValue = value;
            }
        }

        if (correctValue == null || correctValue.isTombstone()) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return new Response(Response.OK, correctValue.getData().array());
    }
}
