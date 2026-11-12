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

import java.util.ArrayList;
import java.util.List;
import io.ikanos.Capability;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.consumes.ClientAdapter;
import tools.jackson.databind.JsonNode;

/**
 * The upstream MCP tasks an execution waits on, cancelled with it (Execution &amp; Resolution
 * Model §7.14.12). A task waiting on an upstream task that asked for input has no worker left to
 * interrupt: its {@link UpstreamCall} sub-run is suspended, and only its journal names the
 * upstream task. Cancelling the execution reads that journal and cancels the upstream task too,
 * also after a restart.
 */
public final class UpstreamTasks {

    private UpstreamTasks() {
    }

    /**
     * Cancels, best effort, the upstream tasks the sub-runs of {@code root} wait on: those of the
     * consumed MCP calls of its steps, at any depth.
     *
     * @return the number of upstream tasks a {@code tasks/cancel} was sent for
     */
    public static int cancelUnder(Capability capability, JournaledRunner runner,
            ExecutionId root) {
        int cancelled = 0;
        for (Waiting waiting : waitingUnder(runner, root)) {
            McpClientAdapter adapter =
                    adapterOf(capability, waiting.task().path("namespace").asString(null));
            if (adapter != null) {
                adapter.cancelTask(waiting.task().path("taskId").asString(),
                        waiting.task().path("credentials").asString(null));
                runner.cancel(waiting.subRun());
                cancelled++;
            }
        }
        return cancelled;
    }

    /** A sub-run waiting on an upstream task, and the task as its journal names it. */
    record Waiting(ExecutionId subRun, JsonNode task) {
    }

    /**
     * The unfinished sub-runs of {@code root}, at any depth, that wait on an upstream task. The
     * index is queried by the root's ID prefix, so no number of other executions in the store
     * can hide one.
     */
    static List<Waiting> waitingUnder(JournaledRunner runner, ExecutionId root) {
        List<ExecutionRecord> unfinished = new ArrayList<>();
        unfinished.addAll(runner.listUnder(root, ExecutionStatus.SUSPENDED));
        unfinished.addAll(runner.listUnder(root, ExecutionStatus.RUNNING));
        List<Waiting> waiting = new ArrayList<>();
        for (ExecutionRecord record : unfinished) {
            JsonNode task = UpstreamCall.openTask(runner.view(record.executionId()));
            if (task != null) {
                waiting.add(new Waiting(record.executionId(), task));
            }
        }
        return waiting;
    }

    private static McpClientAdapter adapterOf(Capability capability, String namespace) {
        if (namespace == null) {
            return null;
        }
        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter instanceof McpClientAdapter mcp && namespace.equals(mcp.getNamespace())) {
                return mcp;
            }
        }
        return null;
    }
}
