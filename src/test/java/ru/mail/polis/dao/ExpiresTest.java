package ru.mail.polis.dao;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mail.polis.TestBase;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ExpiresTest extends TestBase {

    private static final long LIFE_TIME_DELTA = 1000L;
    private static final long DELAY = LIFE_TIME_DELTA * 2;
    private static final CountDownLatch waiter = new CountDownLatch(1);

    @Test
    void recordMustExpire(@TempDir final File data) throws IOException, InterruptedException {
        final ByteBuffer keyBuffer = randomKeyBuffer();
        final ByteBuffer valueBuffer = randomValueBuffer();
        final long expiresTimestamp = System.currentTimeMillis() + LIFE_TIME_DELTA;

        try (final DAO dao = DAOFactory.create(data)) {
            dao.upsert(keyBuffer, valueBuffer, expiresTimestamp);
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer);
            delay(DELAY);
            Assertions.assertThrows(NoSuchElementException.class, () -> dao.get(keyBuffer));
        }
    }

    @Test
    void overwrittenRecordMustExpire(@TempDir final File data) throws IOException, InterruptedException {
        final ByteBuffer keyBuffer = randomKeyBuffer();
        final ByteBuffer valueBuffer1 = randomValueBuffer();
        final ByteBuffer valueBuffer2 = randomValueBuffer();
        final long expiresTimestamp = System.currentTimeMillis() + LIFE_TIME_DELTA;

        try (final DAO dao = DAOFactory.create(data)) {
            // upsert never-expires record with key and value1,
            dao.upsert(keyBuffer, valueBuffer1);
            // check that upsert is successful
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer1);
            // overwrite key-record with value2 and expiresTimestamp
            dao.upsert(keyBuffer, valueBuffer2, expiresTimestamp);
            // check that overwrite is successful
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer2);
            // sleep DELAY (DELAY = 2 * LIFE_TIME_DELTA)
            delay(DELAY);
            // assert that our record has expired
            Assertions.assertThrows(NoSuchElementException.class, () -> dao.get(keyBuffer));
        }
    }

    @Test
    void overwrittenExpireRecordMustNotExpire(@TempDir final File data) throws IOException, InterruptedException {
        final ByteBuffer keyBuffer = randomKeyBuffer();
        final ByteBuffer valueBuffer1 = randomValueBuffer();
        final ByteBuffer valueBuffer2 = randomValueBuffer();
        final long expiresTimestamp = System.currentTimeMillis() + LIFE_TIME_DELTA;

        try (final DAO dao = DAOFactory.create(data)) {
            // upsert record with key, value1, expiresTimestamp
            dao.upsert(keyBuffer, valueBuffer1, expiresTimestamp);
            // check that upsert is successful
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer1);
            // overwrite key-record with value2
            dao.upsert(keyBuffer, valueBuffer2);
            // check that overwrite is successful
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer2);
            // sleep DELAY (DELAY = 2 * LIFE_TIME_DELTA)
            delay(DELAY);
            // assert that our record hasn't expired
            Assertions.assertEquals(dao.get(keyBuffer), valueBuffer2);
        }
    }

    private static void delay(final long milliseconds) throws InterruptedException {
        waiter.await(milliseconds, TimeUnit.MILLISECONDS);
    }
}
