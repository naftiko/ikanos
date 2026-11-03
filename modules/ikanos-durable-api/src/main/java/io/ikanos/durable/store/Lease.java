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
import java.util.Objects;
import io.ikanos.durable.ExecutionId;

/**
 * Exclusive right to run an execution until {@code expiresAt}. Every journal append carries the
 * fencing token, which grows with each new lease, so a stale owner cannot write.
 *
 * @param executionId the execution
 * @param owner the process holding the lease
 * @param fencingToken the token to present on every append
 * @param expiresAt the expiry, in store time
 */
public record Lease(ExecutionId executionId, String owner, long fencingToken, Instant expiresAt) {

    public Lease {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
