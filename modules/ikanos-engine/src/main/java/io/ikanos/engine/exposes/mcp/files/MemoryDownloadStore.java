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

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A {@link DownloadStore} in process memory, bounded in files, in bytes, and in bytes per caller.
 * A replica serves only the links it issued.
 *
 * <p>Every adapter of the process shares {@link #shared()}, so the bound holds for the whole
 * process: at most {@value #MAX_BYTES_UPPER_LIMIT} bytes, and never more than
 * 1/{@value #HEAP_FRACTION} of the maximum heap, since a container image may leave the heap at
 * its default size. One caller may hold at most 1/{@value #CALLER_FRACTION} of it, so a caller
 * that never fetches its links cannot keep the others' results inline. Expired files are dropped
 * on every put and take, and every {@value #SWEEP_SECONDS} seconds, so an idle adapter does not
 * keep them either.</p>
 */
public final class MemoryDownloadStore implements DownloadStore {

    /** Default bound on the files kept at once. */
    public static final int DEFAULT_MAX_FILES = 256;

    /** Upper limit of the bytes the shared store keeps: 256&nbsp;MiB. */
    public static final long MAX_BYTES_UPPER_LIMIT = 256L * 1024 * 1024;

    /** The shared store keeps at most 1/{@value} of the maximum heap. */
    static final int HEAP_FRACTION = 8;

    /** One caller holds at most 1/{@value} of the store's bytes. */
    static final int CALLER_FRACTION = 4;

    /** How often the shared store drops expired files. */
    static final long SWEEP_SECONDS = 30;

    private static volatile MemoryDownloadStore shared;

    private final int maxFiles;
    private final long maxBytes;
    private final long maxBytesPerCaller;
    private final Map<String, StoredDownload> files = new HashMap<>();
    private final Map<String, Long> bytesByCaller = new HashMap<>();
    private long bytes;

    public MemoryDownloadStore(int maxFiles, long maxBytes, long maxBytesPerCaller) {
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        this.maxBytesPerCaller = maxBytesPerCaller;
    }

    /**
     * The store every adapter of the process shares, created on first use with
     * {@link #maxBytesFor(long)} of this JVM's maximum heap and a daemon thread that drops
     * expired files.
     */
    public static MemoryDownloadStore shared() {
        MemoryDownloadStore store = shared;
        if (store == null) {
            synchronized (MemoryDownloadStore.class) {
                store = shared;
                if (store == null) {
                    long maxBytes = maxBytesFor(Runtime.getRuntime().maxMemory());
                    store = new MemoryDownloadStore(DEFAULT_MAX_FILES, maxBytes,
                            maxBytes / CALLER_FRACTION);
                    store.sweepEvery(SWEEP_SECONDS, Clock.systemUTC());
                    shared = store;
                }
            }
        }
        return store;
    }

    /**
     * The bytes the shared store keeps on a heap of {@code maxHeap} bytes: 1/{@value
     * #HEAP_FRACTION} of it, at most {@value #MAX_BYTES_UPPER_LIMIT}.
     */
    static long maxBytesFor(long maxHeap) {
        return Math.min(MAX_BYTES_UPPER_LIMIT, maxHeap / HEAP_FRACTION);
    }

    @Override
    public synchronized boolean put(String token, StoredDownload download, Instant now) {
        dropExpired(now);
        long size = download.bytes().length;
        long callerBytes = bytesByCaller.getOrDefault(download.principal(), 0L);
        if (files.size() >= maxFiles || bytes + size > maxBytes
                || callerBytes + size > maxBytesPerCaller) {
            return false;
        }
        files.put(token, download);
        bytes += size;
        bytesByCaller.merge(download.principal(), size, Long::sum);
        return true;
    }

    @Override
    public synchronized Optional<StoredDownload> take(String token, String principal,
            Instant now) {
        dropExpired(now);
        StoredDownload download = files.get(token);
        if (download == null || !download.principal().equals(principal)) {
            return Optional.empty();
        }
        remove(token, download);
        return Optional.of(download);
    }

    /** Drops the files expired at {@code now}, as the scheduled sweep does. */
    synchronized void sweep(Instant now) {
        dropExpired(now);
    }

    /** @return the number of files kept */
    synchronized int size() {
        return files.size();
    }

    /** @return the bound on the bytes kept */
    long maxBytes() {
        return maxBytes;
    }

    private void sweepEvery(long seconds, Clock clock) {
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcp-download-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(() -> sweep(clock.instant()), seconds, seconds,
                TimeUnit.SECONDS);
    }

    private void dropExpired(Instant now) {
        files.entrySet().removeIf(entry -> {
            boolean expired = !now.isBefore(entry.getValue().expiresAt());
            if (expired) {
                release(entry.getValue());
            }
            return expired;
        });
    }

    private void remove(String token, StoredDownload download) {
        files.remove(token);
        release(download);
    }

    private void release(StoredDownload download) {
        long size = download.bytes().length;
        bytes -= size;
        bytesByCaller.computeIfPresent(download.principal(),
                (caller, held) -> held - size > 0 ? held - size : null);
    }
}
