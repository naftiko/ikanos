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

import java.util.List;
import java.util.Set;
import io.ikanos.durable.ExecutionId;

/** Append-only journal of each execution. */
public interface JournalStore {

    /** The features this store supports. */
    Set<StoreFeature> features();

    /**
     * Appends {@code entries} after {@code expectedSequence}, under the lease identified by
     * {@code fencingToken}. Fails if another writer appended first or holds a newer token.
     */
    void append(ExecutionId executionId, long expectedSequence, long fencingToken,
            List<JournalEntry> entries) throws ConcurrentAppendException;

    /** Reads the journal of an execution, in sequence order. */
    List<JournalEntry> read(ExecutionId executionId);

    /** Deletes the journal of an execution once its retention window has passed. */
    void truncate(ExecutionId executionId);
}
