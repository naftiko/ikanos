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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import io.ikanos.durable.ExecutionId;

/** Runnable executions and due timers, claimed through leases. */
public interface WorkQueue {

    /** Makes an execution runnable at {@code dueAt}, in store time. */
    void enqueue(ExecutionId executionId, Instant dueAt);

    /** Claims the next due execution for {@code owner}, if any. */
    Optional<Lease> claim(String owner, Duration leaseFor);

    /** Extends a lease; returns {@code false} when it was lost. */
    boolean renew(Lease lease, Duration leaseFor);

    /** Releases a lease. */
    void release(Lease lease);
}
