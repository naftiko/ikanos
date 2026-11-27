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
import java.util.Map;
import java.util.Optional;

/**
 * Where hosted uploads of the MCP files extension wait between their {@code PUT} and the retry
 * of the tool call (experimental). Each upload is a slot, keyed by the random token of its
 * target path, reserved at its declared size when the target is issued: the reservations count
 * against the store's quota until the slot is deleted or expires.
 *
 * <p>The durable {@code BlobStore} does not fit: it is keyed by execution, offers no per-file
 * delete nor expiry, and exists only for a durable capability, while an upload belongs to a
 * {@code tools/call} that has no execution yet. An implementation shared by replicas can replace
 * {@link MemoryUploadStore} behind this interface.</p>
 */
public interface UploadStore {

    /**
     * One hosted upload.
     *
     * @param principal the principal the target was issued to: only it may send the bytes
     * @param stateId the id of the upload record sealed in the {@code requestState}
     * @param mimeType the declared media type: the {@code PUT} must send it as its
     *        {@code Content-Type}
     * @param size the declared size: the {@code PUT} must send exactly that many bytes
     * @param writableUntil when the target stops accepting its {@code PUT}
     * @param expiresAt when the {@code requestState} expires: the slot is purged then
     */
    record Slot(String principal, String stateId, String mimeType, long size,
            Instant writableUntil, Instant expiresAt) {
    }

    /**
     * What a {@link #beginWrite} found.
     *
     * @param write the outcome
     * @param slot the slot, when the write {@link Write#STARTED}, or {@code null}
     */
    record Started(Write write, Slot slot) {
    }

    /** The outcome of a {@link #beginWrite}. */
    enum Write {
        /** The slot is now being written by the caller, which must complete or abort. */
        STARTED,
        /** No such slot for this principal, or its target has expired. */
        NOT_FOUND,
        /** The slot was written, or is being written, already: it accepts one write. */
        TAKEN
    }

    /**
     * Reserves the slots of one call, all or none.
     *
     * @return {@code false} when the quota has no room for all of them
     */
    boolean reserve(Map<String, Slot> slots, Instant now);

    /**
     * Starts the one write of a slot, for {@code principal}, before its target expires. A slot
     * asked for by another principal is left as it is.
     */
    Started beginWrite(String token, String principal, Instant now);

    /**
     * Completes a write started by {@link #beginWrite}: the slot now holds {@code bytes}.
     *
     * @return {@code false} when the slot was dropped meanwhile (expired, deleted or cleared):
     *         the bytes are not kept
     */
    boolean completeWrite(String token, byte[] bytes);

    /**
     * Aborts a write started by {@link #beginWrite}: the slot takes a new write, as a client may
     * repeat a failed {@code PUT}.
     */
    void abortWrite(String token);

    /** The slot and the bytes written to it, when it was written and has not expired. */
    Optional<Stored> read(String token, Instant now);

    /** Deletes a slot and its bytes, freeing its share of the quota. */
    void delete(String token);

    /**
     * Drops the slots whose {@code requestState} has expired, and those never written whose
     * target has expired.
     *
     * @return how many slots were dropped
     */
    int purge(Instant now);

    /** Drops every slot, as the adapter that owns the store stops. */
    void clear();

    /** A written slot. */
    record Stored(Slot slot, byte[] bytes) {
    }
}
