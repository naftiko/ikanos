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
import java.util.Map;
import java.util.Objects;
import io.ikanos.durable.ExecutionId;

/**
 * Index entry of an execution.
 *
 * @param executionId the execution
 * @param specDigest digest of the compiled tree the execution is pinned to
 * @param entry the exposed entry it started from (adapter, namespace and operation; opaque here)
 * @param status the current status
 * @param createdAt creation time, in store time
 * @param deadline the deadline, or {@code null}
 * @param labels searchable attributes
 */
public record ExecutionRecord(ExecutionId executionId, String specDigest, String entry,
        ExecutionStatus status, Instant createdAt, Instant deadline, Map<String, String> labels) {

    public ExecutionRecord {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(specDigest, "specDigest");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
