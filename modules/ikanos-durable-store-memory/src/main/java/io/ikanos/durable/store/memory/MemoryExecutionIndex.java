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
package io.ikanos.durable.store.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.ExecutionIndex;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;

/** Execution index in process memory. */
final class MemoryExecutionIndex implements ExecutionIndex {

    private final Map<ExecutionId, ExecutionRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean create(ExecutionRecord record) {
        return records.putIfAbsent(record.executionId(), record) == null;
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionId executionId) {
        return Optional.ofNullable(records.get(executionId));
    }

    @Override
    public void updateStatus(ExecutionId executionId, ExecutionStatus status) {
        records.computeIfPresent(executionId,
                (id, r) -> new ExecutionRecord(r.executionId(), r.specDigest(), r.entry(), status,
                        r.createdAt(), r.deadline(), r.labels()));
    }

    @Override
    public List<ExecutionRecord> list(ExecutionStatus status, int limit) {
        return records.values().stream().filter(r -> r.status() == status)
                .sorted(Comparator.comparing(ExecutionRecord::createdAt)).limit(limit).toList();
    }
}
