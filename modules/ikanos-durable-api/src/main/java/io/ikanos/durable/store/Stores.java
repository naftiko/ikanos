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
package io.ikanos.durable.store;

import java.util.Objects;

/**
 * The storage a durable mode runs on, opened by a {@link StoreProvider}.
 *
 * <p>Closing releases what the provider opened, such as a database file. Stores held in process
 * memory have nothing to release.</p>
 *
 * @param journal the journal of each execution
 * @param index the execution index
 * @param queue runnable executions and due timers
 * @param inbox signals and updates
 * @param blobs large payloads
 * @param onClose what {@link #close()} runs; it must tolerate being run twice
 */
public record Stores(JournalStore journal, ExecutionIndex index, WorkQueue queue,
        SignalInbox inbox, BlobStore blobs, Runnable onClose) implements AutoCloseable {

    public Stores {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(blobs, "blobs");
        onClose = onClose == null ? () -> { } : onClose;
    }

    /** Stores with nothing to release on close. */
    public Stores(JournalStore journal, ExecutionIndex index, WorkQueue queue, SignalInbox inbox,
            BlobStore blobs) {
        this(journal, index, queue, inbox, blobs, null);
    }

    /** Releases what the provider opened; the stores must not be used afterwards. */
    @Override
    public void close() {
        onClose.run();
    }
}
