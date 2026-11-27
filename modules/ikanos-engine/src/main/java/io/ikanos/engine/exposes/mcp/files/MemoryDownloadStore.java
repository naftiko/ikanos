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
import java.util.Map;
import java.util.Optional;

/**
 * A {@link DownloadStore} in process memory, bounded in files and in bytes. A replica serves only
 * the links it issued.
 */
public final class MemoryDownloadStore implements DownloadStore {

    /** Default bound on the files kept at once. */
    public static final int DEFAULT_MAX_FILES = 256;

    /** Default bound on the bytes kept at once: 256&nbsp;MiB. */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    private final int maxFiles;
    private final long maxBytes;
    private final Map<String, StoredDownload> files = new HashMap<>();
    private long bytes;

    public MemoryDownloadStore() {
        this(DEFAULT_MAX_FILES, DEFAULT_MAX_BYTES);
    }

    public MemoryDownloadStore(int maxFiles, long maxBytes) {
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    @Override
    public synchronized boolean put(String token, StoredDownload download, Instant now) {
        dropExpired(now);
        long size = download.bytes().length;
        if (files.size() >= maxFiles || bytes + size > maxBytes) {
            return false;
        }
        files.put(token, download);
        bytes += size;
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
        files.remove(token);
        bytes -= download.bytes().length;
        return Optional.of(download);
    }

    /** @return the number of files kept */
    synchronized int size() {
        return files.size();
    }

    private void dropExpired(Instant now) {
        files.values().removeIf(download -> {
            boolean expired = !now.isBefore(download.expiresAt());
            if (expired) {
                bytes -= download.bytes().length;
            }
            return expired;
        });
    }
}
