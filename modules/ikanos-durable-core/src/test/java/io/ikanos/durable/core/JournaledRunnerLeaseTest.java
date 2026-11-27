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
package io.ikanos.durable.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.JournalStore;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.durable.store.StoreConformanceTest.MutableClock;
import io.ikanos.durable.store.StoreFeature;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;

/**
 * Leases on a store several replicas share: two runners with their own owner names on one store
 * stand for two replicas. The memory store plays the shared store, its journal declaring
 * {@link StoreFeature#SHARED}.
 */
class JournaledRunnerLeaseTest {

    private static final ExecutionId ID = new ExecutionId("task-1");
    private static final NodePath SCREEN = new NodePath("steps/screen");
    private static final NodePath CONFIRM = new NodePath("steps/confirm-hazardous");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private static final PayloadCodec STRINGS = new PayloadCodec() {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object decode(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };

    /** Screen, then wait for the duty officer's confirmation, then complete with it. */
    private static final Interpreter CLEARANCE = view -> {
        if (view.outcome(SCREEN).isEmpty()) {
            return List.of(new Command.ScheduleAction(SCREEN, "screen", null));
        }
        if (view.update(CONFIRM).isEmpty()) {
            return List.of(new Command.AwaitUpdate(CONFIRM, "Confirm 3 containers"));
        }
        return List.of(new Command.Complete("confirmed " + view.update(CONFIRM).get()));
    };

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final Stores memory = MemoryStoreProvider.openStores(clock);
    private final Stores stores = new Stores(shared(memory.journal()), memory.index(),
            memory.queue(), memory.inbox(), memory.blobs());
    private final JournaledRunner podA = runner("pod-a", LEASE);
    private final JournaledRunner podB = runner("pod-b", LEASE);
    private final List<String> calls = new ArrayList<>();
    private final ActionExecutor recording = request -> {
        calls.add(request.path().value());
        return "screened";
    };

    private JournaledRunner runner(String owner, Duration leaseFor) {
        return new JournaledRunner(stores, STRINGS, clock, "sha256:tree", owner, leaseFor);
    }

    @AfterEach
    void stopRenewals() {
        podA.close();
        podB.close();
    }

    /** The memory journal, declaring that several replicas share it. */
    private static JournalStore shared(JournalStore journal) {
        return new JournalStore() {
            @Override
            public Set<StoreFeature> features() {
                Set<StoreFeature> features = EnumSet.copyOf(journal.features());
                features.add(StoreFeature.SHARED);
                return features;
            }

            @Override
            public void append(ExecutionId executionId, long expectedSequence, long fencingToken,
                    List<JournalEntry> entries) throws ConcurrentAppendException {
                journal.append(executionId, expectedSequence, fencingToken, entries);
            }

            @Override
            public List<JournalEntry> read(ExecutionId executionId) {
                return journal.read(executionId);
            }

            @Override
            public void truncate(ExecutionId executionId) {
                journal.truncate(executionId);
            }
        };
    }

    @Test
    void runnerShouldBeSharedOnlyOnAStoreDeclaringIt() {
        assertTrue(podA.isShared());
        assertFalse(new JournaledRunner(memory, STRINGS, clock, "sha256:tree").isShared());
    }

    @Test
    void constructorShouldRefuseALeaseOfNoLength() {
        assertThrows(IllegalArgumentException.class, () -> runner("pod-c", Duration.ZERO));
    }

    @Test
    void runShouldRefuseAnExecutionWhoseLeaseAnotherReplicaHolds() {
        stores.queue().acquire(ID, "pod-b", LEASE).orElseThrow();

        ExecutionBusyException e = assertThrows(ExecutionBusyException.class,
                () -> podA.run(ExecutionRequest.of(ID, null), CLEARANCE, recording));

        assertEquals(ID, e.executionId());
        assertTrue(calls.isEmpty());
        assertTrue(podA.record(ID).isEmpty(), "Nothing is written without the lease");
    }

    @Test
    void runShouldTakeTheExecutionOverOnceTheHoldersLeaseExpired() {
        stores.queue().acquire(ID, "pod-b", LEASE).orElseThrow();
        clock.advance(LEASE);

        ExecutionResult result = podA.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertInstanceOf(ExecutionResult.Suspended.class, result);
        assertEquals(List.of("steps/screen"), calls);
    }

    @Test
    void runShouldReleaseItsLeaseWhenItEnds() {
        podA.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertTrue(stores.queue().acquire(ID, "pod-b", LEASE).isPresent());
    }

    @Test
    void runShouldLetAnotherReplicaResumeAWaitingExecution() {
        podA.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        podB.deliver(ID, CONFIRM, "yes");

        ExecutionResult result = podB.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);

        assertEquals(new ExecutionResult.Completed("confirmed yes"), result);
        assertEquals(List.of("steps/screen"), calls, "The screening is restored, not run again");
    }

    @Test
    void runShouldRunAgainWhenAnUpdateArrivedWhileItHeldTheLease() {
        // Pod B's answer lands while pod A screens: pod B cannot run the execution then, so pod A
        // reads the answer once it would otherwise stop and wait.
        ActionExecutor answeredMeanwhile = request -> {
            podB.deliver(ID, CONFIRM, "yes");
            assertThrows(ExecutionBusyException.class,
                    () -> podB.run(ExecutionRequest.of(ID, null), CLEARANCE, recording));
            return "screened";
        };

        ExecutionResult result = podA.run(ExecutionRequest.of(ID, null), CLEARANCE,
                answeredMeanwhile);

        assertEquals(new ExecutionResult.Completed("confirmed yes"), result);
        assertEquals(ExecutionStatus.COMPLETED, podA.status(ID).orElseThrow());
    }

    @Test
    void runShouldStopWhenAnotherReplicaTookTheExecutionOver() {
        // Pod A stalls in the screening past its lease; pod B takes over and runs to the question.
        ActionExecutor stalled = request -> {
            clock.advance(LEASE);
            assertInstanceOf(ExecutionResult.Suspended.class,
                    podB.run(ExecutionRequest.of(ID, null), CLEARANCE, recording));
            return "screened late";
        };

        assertThrows(ExecutionBusyException.class,
                () -> podA.run(ExecutionRequest.of(ID, null), CLEARANCE, stalled));

        assertEquals("screened", podB.view(ID).outcome(SCREEN).map(outcome ->
                ((io.ikanos.durable.ActionOutcome.Succeeded) outcome).value()).orElseThrow(),
                "The stale owner's outcome is refused by the journal");
    }

    @Test
    void runShouldStopAtItsNextAppendOnceItsLeaseWasLost() throws InterruptedException {
        JournaledRunner shortLease = runner("pod-c", Duration.ofMillis(300));
        try {
            ActionExecutor slow = request -> {
                clock.advance(Duration.ofSeconds(1));
                try {
                    // Long enough for a renewal to run and find the lease expired.
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "screened";
            };

            assertThrows(ExecutionBusyException.class,
                    () -> shortLease.run(ExecutionRequest.of(ID, null), CLEARANCE, slow));
        } finally {
            shortLease.close();
        }
    }

    @Test
    void failIfShouldLeaveAnExecutionAnotherReplicaHolds() {
        podA.run(ExecutionRequest.of(ID, null), CLEARANCE, recording);
        stores.queue().acquire(ID, "pod-b", LEASE).orElseThrow();

        assertFalse(podA.failIf(ID, ExecutionStatus.SUSPENDED, view -> true,
                new Failure("expired", "Not answered in time")));
        assertEquals(ExecutionStatus.SUSPENDED, podA.status(ID).orElseThrow());
    }

    @Test
    void failShouldReportAnExecutionAnotherReplicaHolds() {
        podA.start(ExecutionRequest.of(ID, "arguments"), "mcp:ops/clear", java.util.Map.of());
        stores.queue().acquire(ID, "pod-b", LEASE).orElseThrow();

        assertThrows(ExecutionBusyException.class,
                () -> podA.fail(ID, new Failure("spec-changed", "Changed")));
        assertEquals(ExecutionStatus.RUNNING, podA.status(ID).orElseThrow());
    }

    @Test
    void startShouldRefuseAnExecutionWhoseLeaseAnotherReplicaHolds() {
        stores.queue().acquire(ID, "pod-b", LEASE).orElseThrow();

        assertThrows(ExecutionBusyException.class, () -> podA.start(
                ExecutionRequest.of(ID, "arguments"), "mcp:ops/clear", java.util.Map.of()));
        assertTrue(podA.record(ID).isEmpty());
    }

    @Test
    void runShouldTakeNoLeaseOnAStoreOfOneProcess() {
        JournaledRunner single = new JournaledRunner(memory, STRINGS, clock, "sha256:tree");
        memory.queue().acquire(ID, "pod-b", LEASE).orElseThrow();

        assertInstanceOf(ExecutionResult.Suspended.class,
                single.run(ExecutionRequest.of(ID, null), CLEARANCE, recording));
    }
}
