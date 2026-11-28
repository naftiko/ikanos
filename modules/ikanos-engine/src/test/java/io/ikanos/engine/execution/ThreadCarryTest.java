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
package io.ikanos.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.util.OperationStepExecutor;

/** The request state a track carries onto its own thread (#1169). */
public class ThreadCarryTest {

    private record Seen(boolean keepsErrorBodies, List<String> chain, String traceId) {
    }

    @Test
    void runShouldGiveAnotherThreadTheErrorBodySettingTheCallChainAndTheLogContext()
            throws Exception {
        ThreadCarry carry;
        MDC.put("trace_id", "t-1");
        try (OperationStepExecutor.UpstreamErrorBodies keep =
                OperationStepExecutor.keepUpstreamErrorBodies()) {
            carry = AggregateFlow.withCallChain(List.of("billing.remind"), ThreadCarry::capture);
        } finally {
            MDC.remove("trace_id");
        }

        Seen seen;
        try (ExecutorService other = Executors.newVirtualThreadPerTaskExecutor()) {
            seen = other.submit(() -> carry.run(() -> new Seen(
                    OperationStepExecutor.keepsUpstreamErrorBodies(), AggregateFlow.callChain(),
                    MDC.get("trace_id")))).get();
        }

        assertTrue(seen.keepsErrorBodies());
        assertEquals(List.of("billing.remind"), seen.chain());
        assertEquals("t-1", seen.traceId());
    }

    @Test
    void runShouldRestoreTheThreadsOwnStateAfterwards() throws Exception {
        ThreadCarry carry = AggregateFlow.withCallChain(List.of("a.b"), ThreadCarry::capture);

        carry.run(() -> null);

        assertFalse(OperationStepExecutor.keepsUpstreamErrorBodies());
        assertTrue(AggregateFlow.callChain().isEmpty());
        assertNull(MDC.get("trace_id"));
    }
}
