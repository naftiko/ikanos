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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.FaultInjectingStores;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.execution.Durability;
import io.ikanos.engine.execution.JsonPayloadCodec;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.util.IkanosMappers;

public class McpTaskServiceTest {

    private final McpTaskService service = new McpTaskService(
            Durability.fromSpec(new DurabilitySpec("memory")).runner(), Clock.systemUTC(), null);

    @Test
    void anotherPrincipalShouldNeitherSeeNorCancelATask() {
        String taskId = service.create("mcp:shipyard-ops/clear-for-departure", "agent-7",
                IkanosMappers.json().createObjectNode(),
                () -> IkanosMappers.json().createObjectNode()).path("taskId").asString();

        assertTrue(service.get(taskId, "agent-7").isPresent());
        assertTrue(service.get(taskId, "agent-8").isEmpty());
        assertFalse(service.cancel(taskId, "agent-8"));
        assertTrue(service.cancel(taskId, "agent-7"));
    }

    @Test
    void theStoreShouldKeepADigestOfThePrincipalNotThePrincipal() {
        service.create("mcp:shipyard-ops/clear-for-departure", "agent-7", null,
                () -> IkanosMappers.json().createObjectNode());

        assertEquals(64, McpTaskService.digest("agent-7").length());
        assertFalse(McpTaskService.digest("agent-7").contains("agent-7"));
        assertFalse(McpTaskService.digest("agent-7").equals(McpTaskService.digest("agent-8")));
    }

    @Test
    void resumeShouldSkipEntriesItDoesNotServe() {
        service.create("mcp:shipyard-ops/clear-for-departure", "agent-7", null,
                () -> {
                    Thread.sleep(200);
                    return IkanosMappers.json().createObjectNode();
                });

        assertEquals(0, service.resume((entry, arguments) -> null));
    }

    @Test
    void resumeShouldFailATaskWhoseArgumentsWereNeverRecorded() {
        Stores stores = MemoryStoreProvider.openStores(Clock.systemUTC());
        JournaledRunner beforeCrash = new JournaledRunner(FaultInjectingStores.wrap(stores,
                FaultInjectingStores.Boundary.BEFORE_STARTED, 1), new JsonPayloadCodec(),
                Clock.systemUTC(), "sha256:spec");
        assertThrows(FaultInjectingStores.Crash.class,
                () -> new McpTaskService(beforeCrash, Clock.systemUTC(), null).create(
                        "mcp:shipyard-ops/clear-for-departure", "agent-7",
                        IkanosMappers.json().createObjectNode().put("voyageId", "VOY-2026-042"),
                        () -> IkanosMappers.json().createObjectNode()));

        JournaledRunner afterRestart =
                new JournaledRunner(stores, new JsonPayloadCodec(), Clock.systemUTC(), "sha256:spec");
        ExecutionId task = afterRestart.list(ExecutionStatus.RUNNING, 10).get(0).executionId();
        AtomicInteger runs = new AtomicInteger();
        int resumed = new McpTaskService(afterRestart, Clock.systemUTC(), null).resume(
                (entry, arguments) -> () -> {
                    runs.incrementAndGet();
                    return IkanosMappers.json().createObjectNode();
                });

        assertEquals(0, resumed);
        assertEquals(0, runs.get(), "A task without its arguments must not run");
        assertEquals(ExecutionStatus.FAILED, afterRestart.status(task).orElseThrow());
        assertEquals(McpTaskService.INCOMPLETE, ((ExecutionResult.Failed) afterRestart
                .result(task).orElseThrow()).failure().errorClass());
    }

    @Test
    void anUnknownTaskShouldBeNeitherFoundNorCancelled() {
        assertTrue(service.get("unknown", "agent-7").isEmpty());
        assertTrue(service.get(null, "agent-7").isEmpty());
        assertFalse(service.cancel("unknown", "agent-7"));
    }
}
