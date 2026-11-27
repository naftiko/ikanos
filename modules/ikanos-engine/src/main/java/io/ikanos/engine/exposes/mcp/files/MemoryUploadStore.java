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
 * An {@link UploadStore} in memory, bounded by the adapter's quota of bytes, a number of slots,
 * and a {@link FileMemoryBudget}: the process-wide {@link FileMemoryBudget#shared()} that hosted
 * download links draw from too, in total and per caller. Each slot counts at its declared size
 * from its reservation, so the bytes of a {@code PUT} always fit. Slots are per process: a target
 * is served by the replica that issued it.
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
    private final FileMemoryBudget budget;

    /**
     * The principal of every request when the adapter cannot tell its callers apart, or
     * {@code null}: its slots are not held to a per-caller share of the budget.
     */
    private final String sharedPrincipal;
    private final Map<String, Entry> entries = new HashMap<>();
    private long reserved;

    /** A store bounded by {@code quota} alone, for tests. */
    public MemoryUploadStore(long quota) {
        this(quota, DEFAULT_MAX_SLOTS);
    }

    /** A store bounded by {@code quota} and {@code maxSlots} alone, for tests. */
    public MemoryUploadStore(long quota, int maxSlots) {
        this(quota, maxSlots, new FileMemoryBudget(Long.MAX_VALUE, Long.MAX_VALUE), null);
    }

    /**
     * @param quota the adapter's quota: the most bytes its slots reserve at once
     * @param budget the process-wide budget the slots also draw from
     * @param sharedPrincipal the principal every request has when the adapter cannot tell its
     *        callers apart (no caller identity in its authentication), or {@code null}: such
     *        slots draw on the budget's total only, the adapter's quota bounding them
     */
    public MemoryUploadStore(long quota, int maxSlots, FileMemoryBudget budget,
            String sharedPrincipal) {
        this.quota = quota;
        this.maxSlots = maxSlots;
        this.budget = budget;
        this.sharedPrincipal = sharedPrincipal;
    }

    /** The key a slot of {@code principal} takes its bytes under in the budget. */
    private String budgetKey(String principal) {
        return principal.equals(sharedPrincipal) ? null : principal;
    }

    @Override
    public synchronized boolean reserve(Map<String, Slot> slots, Instant now) {
        purge(now);
        long size = 0;
        try {
            for (Slot slot : slots.values()) {
                size = Math.addExact(size, slot.size());
            }
        } catch (ArithmeticException e) {
            // Declared sizes no store could hold
            return false;
        }
        if (entries.size() + slots.size() > maxSlots || size > quota - reserved) {
            return false;
        }
        // Taken from the process-wide budget per caller, all or none. The sum fits: it is at
        // most the total checked above
        Map<String, Long> byCaller = new HashMap<>();
        Map<String, Long> shared = new HashMap<>();
        for (Slot slot : slots.values()) {
            String key = budgetKey(slot.principal());
            (key == null ? shared : byCaller).merge(key == null ? "" : key, slot.size(),
                    Long::sum);
        }
        Map<String, Long> taken = new HashMap<>();
        long sharedSize = shared.getOrDefault("", 0L);
        if (sharedSize > 0 && !budget.acquire(null, sharedSize)) {
            return false;
        }
        for (Map.Entry<String, Long> caller : byCaller.entrySet()) {
            if (!budget.acquire(caller.getKey(), caller.getValue())) {
                taken.forEach(budget::release);
                if (sharedSize > 0) {
                    budget.release(null, sharedSize);
                }
                return false;
            }
            taken.put(caller.getKey(), caller.getValue());
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
    public synchronized boolean completeWrite(String token, byte[] bytes) {
        Entry entry = entries.get(token);
        if (entry == null || !entry.writing) {
            return false;
        }
        entry.bytes = bytes;
        entry.writing = false;
        return true;
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
            free(entry);
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
                free(entry);
                dropped++;
            }
        }
        return dropped;
    }

    @Override
    public synchronized void clear() {
        entries.values().forEach(this::free);
        entries.clear();
    }

    private void free(Entry entry) {
        reserved -= entry.slot.size();
        budget.release(budgetKey(entry.slot.principal()), entry.slot.size());
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
