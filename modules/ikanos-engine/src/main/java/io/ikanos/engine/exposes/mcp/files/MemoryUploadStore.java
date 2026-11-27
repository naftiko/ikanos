/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.engine.exposes.mcp.files;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link UploadStore} in memory, bounded by a quota of bytes and a number of slots. Each slot
 * counts at its declared size from its reservation, so the bytes of a {@code PUT} always fit.
 * Slots are per process: a target is served by the replica that issued it.
 */
public final class MemoryUploadStore implements UploadStore {

    /** Default bound on the slots kept at once. */
    public static final int DEFAULT_MAX_SLOTS = 10_000;

    private static final class Entry {
        final Slot slot;
        byte[] bytes;
        boolean writing;

        Entry(Slot slot) {
            this.slot = slot;
        }
    }

    private final long quota;
    private final int maxSlots;
    private final Map<String, Entry> entries = new HashMap<>();
    private long reserved;

    public MemoryUploadStore(long quota) {
        this(quota, DEFAULT_MAX_SLOTS);
    }

    public MemoryUploadStore(long quota, int maxSlots) {
        this.quota = quota;
        this.maxSlots = maxSlots;
    }

    @Override
    public synchronized boolean reserve(Map<String, Slot> slots, Instant now) {
        purge(now);
        long size = 0;
        for (Slot slot : slots.values()) {
            size += slot.size();
        }
        if (entries.size() + slots.size() > maxSlots || size > quota - reserved) {
            return false;
        }
        slots.forEach((token, slot) -> entries.put(token, new Entry(slot)));
        reserved += size;
        return true;
    }

    @Override
    public synchronized Started beginWrite(String token, String principal, Instant now) {
        Entry entry = entries.get(token);
        if (entry == null || !entry.slot.principal().equals(principal)
                || !now.isBefore(entry.slot.writableUntil())) {
            return new Started(Write.NOT_FOUND, null);
        }
        if (entry.writing || entry.bytes != null) {
            return new Started(Write.TAKEN, null);
        }
        entry.writing = true;
        return new Started(Write.STARTED, entry.slot);
    }

    @Override
    public synchronized void completeWrite(String token, byte[] bytes) {
        Entry entry = entries.get(token);
        if (entry != null && entry.writing) {
            entry.bytes = bytes;
            entry.writing = false;
        }
    }

    @Override
    public synchronized void abortWrite(String token) {
        Entry entry = entries.get(token);
        if (entry != null) {
            entry.writing = false;
        }
    }

    @Override
    public synchronized Optional<Stored> read(String token, Instant now) {
        Entry entry = entries.get(token);
        if (entry == null || entry.bytes == null || !now.isBefore(entry.slot.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(new Stored(entry.slot, entry.bytes));
    }

    @Override
    public synchronized void delete(String token) {
        Entry entry = entries.remove(token);
        if (entry != null) {
            reserved -= entry.slot.size();
        }
    }

    @Override
    public synchronized int purge(Instant now) {
        int dropped = 0;
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
            Entry entry = it.next();
            boolean unwritten = entry.bytes == null && !entry.writing
                    && !now.isBefore(entry.slot.writableUntil());
            if (unwritten || !now.isBefore(entry.slot.expiresAt())) {
                it.remove();
                reserved -= entry.slot.size();
                dropped++;
            }
        }
        return dropped;
    }

    /** @return the bytes reserved by the slots kept */
    synchronized long reserved() {
        return reserved;
    }

    /** @return the number of slots kept */
    synchronized int size() {
        return entries.size();
    }
}
