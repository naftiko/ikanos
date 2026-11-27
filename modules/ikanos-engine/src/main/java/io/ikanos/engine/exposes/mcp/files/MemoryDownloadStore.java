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
 * <p>Every adapter of the process shares {@link #shared()}, which draws its bytes from the
 * process-wide {@link FileMemoryBudget#shared()}, also used by hosted uploads: so the bound holds
 * for the whole process, at most {@value #MAX_BYTES_UPPER_LIMIT} bytes, and never more than
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
    public static final long MAX_BYTES_UPPER_LIMIT = FileMemoryBudget.MAX_BYTES_UPPER_LIMIT;

    /** The shared store keeps at most 1/{@value} of the maximum heap. */
    static final int HEAP_FRACTION = FileMemoryBudget.HEAP_FRACTION;

    /** One caller holds at most 1/{@value} of the store's bytes. */
    static final int CALLER_FRACTION = FileMemoryBudget.CALLER_FRACTION;

    /** How often the shared store drops expired files. */
    static final long SWEEP_SECONDS = 30;

    private static volatile MemoryDownloadStore shared;

    private final int maxFiles;
    private final FileMemoryBudget budget;
    private final Map<String, StoredDownload> files = new HashMap<>();

    public MemoryDownloadStore(int maxFiles, long maxBytes, long maxBytesPerCaller) {
        this(maxFiles, new FileMemoryBudget(maxBytes, maxBytesPerCaller));
    }

    /** A store that draws its bytes from {@code budget}, which other stores may share. */
    public MemoryDownloadStore(int maxFiles, FileMemoryBudget budget) {
        this.maxFiles = maxFiles;
        this.budget = budget;
    }

    /**
     * The store every adapter of the process shares, created on first use on the process-wide
     * {@link FileMemoryBudget#shared()} and with a daemon thread that drops expired files.
     */
    public static MemoryDownloadStore shared() {
        MemoryDownloadStore store = shared;
        if (store == null) {
            synchronized (MemoryDownloadStore.class) {
                store = shared;
                if (store == null) {
                    store = new MemoryDownloadStore(DEFAULT_MAX_FILES,
                            FileMemoryBudget.shared());
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
        return FileMemoryBudget.maxBytesFor(maxHeap);
    }

    @Override
    public synchronized boolean put(String token, StoredDownload download, Instant now) {
        dropExpired(now);
        long size = download.bytes().length;
        if (files.size() >= maxFiles || !budget.acquire(download.principal(), size)) {
            return false;
        }
        files.put(token, download);
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
        return budget.maxBytes();
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
        budget.release(download.principal(), download.bytes().length);
    }
}
