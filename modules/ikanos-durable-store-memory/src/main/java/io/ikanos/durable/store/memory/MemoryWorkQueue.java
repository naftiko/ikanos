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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.WorkQueue;

/** Work queue in process memory; leases expire on the given clock. */
final class MemoryWorkQueue implements WorkQueue {

    private final Clock clock;
    private final Map<ExecutionId, Instant> due = new HashMap<>();
    private final Map<ExecutionId, Lease> leases = new HashMap<>();
    private long fencingTokens;

    MemoryWorkQueue(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void enqueue(ExecutionId executionId, Instant dueAt) {
        due.put(executionId, dueAt);
    }

    @Override
    public synchronized Optional<Lease> claim(String owner, Duration leaseFor) {
        Instant now = clock.instant();
        Optional<ExecutionId> next = due.entrySet().stream()
                .filter(e -> !e.getValue().isAfter(now))
                .filter(e -> !isLeased(e.getKey(), now))
                .sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).findFirst();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Lease lease = new Lease(next.get(), owner, ++fencingTokens, now.plus(leaseFor));
        due.remove(next.get());
        leases.put(next.get(), lease);
        return Optional.of(lease);
    }

    @Override
    public synchronized boolean renew(Lease lease, Duration leaseFor) {
        Lease current = leases.get(lease.executionId());
        if (current == null || current.fencingToken() != lease.fencingToken()
                || current.expiresAt().isBefore(clock.instant())) {
            return false;
        }
        leases.put(lease.executionId(), new Lease(lease.executionId(), lease.owner(),
                lease.fencingToken(), clock.instant().plus(leaseFor)));
        return true;
    }

    @Override
    public synchronized void release(Lease lease) {
        Lease current = leases.get(lease.executionId());
        if (current != null && current.fencingToken() == lease.fencingToken()) {
            leases.remove(lease.executionId());
        }
    }

    private boolean isLeased(ExecutionId executionId, Instant now) {
        Lease lease = leases.get(executionId);
        return lease != null && lease.expiresAt().isAfter(now);
    }
}
