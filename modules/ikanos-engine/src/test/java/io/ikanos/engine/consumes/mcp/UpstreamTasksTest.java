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
package io.ikanos.engine.consumes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;
import io.ikanos.engine.execution.JsonPayloadCodec;
import io.ikanos.spec.util.IkanosMappers;

public class UpstreamTasksTest {

    @Test
    void manyOtherWaitingExecutionsShouldNotHideTheUpstreamTaskOfACancelledOne() {
        Stores stores = MemoryStoreProvider.openStores(Clock.systemUTC());
        JournaledRunner runner =
                new JournaledRunner(stores, new JsonPayloadCodec(), Clock.systemUTC(), "test");
        // More older waiting executions than any one listing of the store used to return.
        Instant older = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 10_001; i++) {
            stores.index().create(new ExecutionRecord(new ExecutionId("other-" + i), "test",
                    null, ExecutionStatus.SUSPENDED, older.plusMillis(i), null, Map.of()));
        }
        ExecutionId root = ExecutionId.random();
        ExecutionId subRun = new ExecutionId(root.value() + "/steps/fa");

        // The consumed call's sub-run: its upstream task asked for input, so it waits.
        ExecutionResult result = runner.run(ExecutionRequest.of(subRun, null),
                view -> view.outcome(NodePath.of("create")).isEmpty()
                        ? List.of(new Command.ScheduleAction(NodePath.of("create"), "create",
                                null))
                        : List.of(new Command.AwaitUpdate(NodePath.of("update").visit(1),
                                "answers")),
                action -> IkanosMappers.json().createObjectNode().put("kind", "task-ask")
                        .put("taskId", "up-1").put("namespace", "customs"));
        assertEquals(ExecutionResult.Suspended.class, result.getClass());

        List<UpstreamTasks.Waiting> waiting = UpstreamTasks.waitingUnder(runner, root);

        assertEquals(List.of(subRun), waiting.stream().map(UpstreamTasks.Waiting::subRun)
                .toList());
        assertEquals("up-1", waiting.get(0).task().path("taskId").asString());
    }
}
