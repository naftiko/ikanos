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

import java.util.HashMap;
import java.util.Map;

/**
 * The bytes the files extension may hold in process memory, in total and per caller: hosted
 * download links ({@link MemoryDownloadStore}) and hosted uploads ({@link MemoryUploadStore})
 * draw from the same {@link #shared()} budget, so together they never hold more than one bound,
 * whatever the number of adapters.
 *
 * <p>The shared budget is 1/{@value #HEAP_FRACTION} of the maximum heap, at most
 * {@value #MAX_BYTES_UPPER_LIMIT} bytes, since a container image may leave the heap at its
 * default size. One caller may hold at most 1/{@value #CALLER_FRACTION} of it, so a caller that
 * never fetches its links nor finishes its uploads cannot take the room of the others.</p>
 */
public final class FileMemoryBudget {

    /** Upper limit of the shared budget: 256&nbsp;MiB. */
    public static final long MAX_BYTES_UPPER_LIMIT = 256L * 1024 * 1024;

    /** The shared budget is at most 1/{@value} of the maximum heap. */
    static final int HEAP_FRACTION = 8;

    /** One caller holds at most 1/{@value} of a budget. */
    static final int CALLER_FRACTION = 4;

    private static volatile FileMemoryBudget shared;

    private final long maxBytes;
    private final long maxBytesPerCaller;
    private final Map<String, Long> bytesByCaller = new HashMap<>();
    private long bytes;

    public FileMemoryBudget(long maxBytes, long maxBytesPerCaller) {
        this.maxBytes = maxBytes;
        this.maxBytesPerCaller = maxBytesPerCaller;
    }

    /** The budget every store of the process shares, sized from this JVM's maximum heap. */
    public static FileMemoryBudget shared() {
        FileMemoryBudget budget = shared;
        if (budget == null) {
            synchronized (FileMemoryBudget.class) {
                budget = shared;
                if (budget == null) {
                    long max = maxBytesFor(Runtime.getRuntime().maxMemory());
                    budget = new FileMemoryBudget(max, max / CALLER_FRACTION);
                    shared = budget;
                }
            }
        }
        return budget;
    }

    /**
     * The shared budget on a heap of {@code maxHeap} bytes: 1/{@value #HEAP_FRACTION} of it, at
     * most {@value #MAX_BYTES_UPPER_LIMIT}.
     */
    static long maxBytesFor(long maxHeap) {
        return Math.min(MAX_BYTES_UPPER_LIMIT, maxHeap / HEAP_FRACTION);
    }

    /**
     * Takes {@code size} bytes for {@code caller}, when both the total and the caller's share
     * have room.
     *
     * @return {@code false}, taking nothing, when either has no room
     */
    public synchronized boolean acquire(String caller, long size) {
        long held = bytesByCaller.getOrDefault(caller, 0L);
        if (size < 0 || bytes + size > maxBytes || held + size > maxBytesPerCaller) {
            return false;
        }
        bytes += size;
        bytesByCaller.merge(caller, size, Long::sum);
        return true;
    }

    /** Gives back {@code size} bytes {@code caller} acquired. */
    public synchronized void release(String caller, long size) {
        bytes -= size;
        bytesByCaller.computeIfPresent(caller,
                (key, held) -> held - size > 0 ? held - size : null);
    }

    /** @return the bound on the bytes held */
    public long maxBytes() {
        return maxBytes;
    }

    /** @return the bytes held now */
    synchronized long used() {
        return bytes;
    }
}
