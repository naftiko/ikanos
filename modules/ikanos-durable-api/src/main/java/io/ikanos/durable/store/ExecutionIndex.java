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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import io.ikanos.durable.ExecutionId;

/** Index of executions: creation, status and listing. */
public interface ExecutionIndex {

    /**
     * Creates an execution. Returns {@code false}, and changes nothing, when one with the same ID
     * exists: a second start with the same ID is harmless.
     */
    boolean create(ExecutionRecord record);

    /** Finds an execution by ID. */
    Optional<ExecutionRecord> find(ExecutionId executionId);

    /** Sets the status of an execution. */
    void updateStatus(ExecutionId executionId, ExecutionStatus status);

    /**
     * Sets the status of an execution only if it is still {@code expected}, atomically. Returns
     * {@code false}, and changes nothing, when the status is another one or the execution does not
     * exist: a status read earlier cannot overwrite one written since.
     */
    boolean compareAndSetStatus(ExecutionId executionId, ExecutionStatus expected,
            ExecutionStatus status);

    /** Lists up to {@code limit} executions with {@code status}, oldest first. */
    List<ExecutionRecord> list(ExecutionStatus status, int limit);

    /**
     * Lists up to {@code limit} executions with {@code status} and a label {@code key}, oldest
     * first, filtered before the limit: executions without the label, however many, never take a
     * place.
     *
     * @param value the label's value, or {@code null} for any value
     * @param createdBefore only executions created strictly before it, or {@code null} for all
     */
    List<ExecutionRecord> listLabelled(ExecutionStatus status, String key, String value,
            Instant createdBefore, int limit);

    /**
     * Lists up to {@code limit} executions with {@code status} and a label {@code key}, oldest
     * first, leaving out before the limit those whose lease, in the store sharing this index,
     * has not expired at {@code now}: the executions no replica runs. A store whose executions
     * are never leased lists them all.
     */
    default List<ExecutionRecord> listUnleased(ExecutionStatus status, String key, Instant now,
            int limit) {
        return listLabelled(status, key, null, null, limit);
    }

    /** Finds the executions labelled {@code key} = {@code value}, oldest first. */
    List<ExecutionRecord> findByLabel(String key, String value);

    /**
     * Lists every execution with {@code status} whose ID starts with {@code prefix}, oldest
     * first: the sub-runs of an execution, named {@code <execution>/<path>}, whatever else the
     * index holds. The default filters the whole status; a store should narrow it in its query.
     */
    default List<ExecutionRecord> listUnder(String prefix, ExecutionStatus status) {
        return list(status, Integer.MAX_VALUE).stream()
                .filter(record -> record.executionId().value().startsWith(prefix)).toList();
    }
}
