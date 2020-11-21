/*
 * Copyright 2020 (c) Odnoklassniki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.mail.polis.Record;
import ru.mail.polis.dao.alexander.marashov.Cell;
import ru.mail.polis.dao.alexander.marashov.Value;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Storage interface.
 *
 * @author Vadim Tsesko
 * @author Dmitry Schitinin
 */
public interface DAO extends Closeable {

    /**
     * Provides "row" iterator (possibly empty) over {@link Cell}s starting at "from" key (inclusive)
     * in <b>ascending</b> order according to {@link Cell#COMPARATOR}.
     */
    @NotNull
    Iterator<Cell> cellIterator(@NotNull ByteBuffer from) throws IOException;

    /**
     * Provides iterator (possibly empty) over {@link Record}s starting at "from" key (inclusive)
     * in <b>ascending</b> order according to {@link Record#compareTo(Record)}.
     * N.B. The iterator should be obtained as fast as possible, e.g.
     * one should not "seek" to start point ("from" element) in linear time ;)
     */
    @NotNull
    Iterator<Record> iterator(@NotNull ByteBuffer from) throws IOException;

    /**
     * Provides iterator (possibly empty) over {@link Record}s starting at "from" key (inclusive)
     * until given "to" key (exclusive) in <b>ascending</b> order according to {@link Record#compareTo(Record)}.
     * N.B. The iterator should be obtained as fast as possible, e.g.
     * one should not "seek" to start point ("from" element) in linear time ;)
     */
    @NotNull
    default Iterator<Record> range(
            @NotNull ByteBuffer from,
            @Nullable ByteBuffer to) throws IOException {
        if (to == null) {
            return iterator(from);
        }

        if (from.compareTo(to) > 0) {
            return Iters.empty();
        }

        final Record bound = Record.of(to, ByteBuffer.allocate(0));
        return Iters.until(iterator(from), bound);
    }

    /**
     * Obtains {@link Record} corresponding to given key.
     *
     * @throws NoSuchElementException if no such record
     */
    @NotNull
    default ByteBuffer get(@NotNull ByteBuffer key) throws IOException, NoSuchElementException {
        final Iterator<Record> iter = iterator(key);
        if (!iter.hasNext()) {
            throw new NoSuchElementException("Not found");
        }

        final Record next = iter.next();
        if (next.getKey().equals(key)) {
            return next.getValue();
        } else {
            throw new NoSuchElementException("Not found");
        }
    }

    /**
     * Obtains {@link Value} corresponding to given key.
     *
     * @throws NoSuchElementException if no such cell
     */
    @NotNull
    default Value rowGet(@NotNull ByteBuffer key) throws IOException, NoSuchElementException {
        final Iterator<Cell> iter = cellIterator(key);
        if (!iter.hasNext()) {
            throw new NoSuchElementException("Row value not found");
        }

        final Cell next = iter.next();
        final ByteBuffer foundKey = next.getKey();
        if (foundKey.equals(key)) {
            return next.getValue();
        } else {
            throw new NoSuchElementException("Row value not found");
        }
    }

    /**
     * Inserts or updates value by given key.
     */
    void upsert(
            @NotNull ByteBuffer key,
            @NotNull ByteBuffer value
    ) throws IOException;

    /**
     * Inserts or updates value by given key that expires in the future.
     */
    void upsert(
            @NotNull ByteBuffer key,
            @NotNull ByteBuffer value,
            long expiresTimestamp
    ) throws IOException;

    /**
     * Removes value by given key.
     */
    void remove(@NotNull ByteBuffer key) throws IOException;

    /**
     * Perform compaction
     */
    default void compact() throws IOException {
        // Implement me when you get to stage 3
    }
}
