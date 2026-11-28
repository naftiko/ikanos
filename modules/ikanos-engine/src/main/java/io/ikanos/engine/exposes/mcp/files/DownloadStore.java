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
import java.util.Optional;

/**
 * Keeps the files of hosted download targets until they are fetched once or expire.
 */
public interface DownloadStore {

    /** A file waiting to be fetched by the caller it was issued to. */
    record StoredDownload(String principal, String name, String mediaType, byte[] bytes,
            Instant expiresAt) {
    }

    /**
     * Keeps {@code download} under {@code token}.
     *
     * @param now the current time, to drop expired files first
     * @return {@code false} when the store is full: the caller then returns the file inline
     */
    boolean put(String token, StoredDownload download, Instant now);

    /**
     * Removes and returns the file kept under {@code token}, when it was issued to
     * {@code principal} and has not expired. A file asked for by another principal stays.
     */
    Optional<StoredDownload> take(String token, String principal, Instant now);
}
