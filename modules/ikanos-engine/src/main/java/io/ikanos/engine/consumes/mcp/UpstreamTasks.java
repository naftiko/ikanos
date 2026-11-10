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

    /** Most unfinished executions examined for one cancel. */
    static final int SCAN_LIMIT = 10_000;

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
        String under = root.value() + "/";
        List<ExecutionRecord> unfinished = new ArrayList<>();
        unfinished.addAll(runner.list(ExecutionStatus.SUSPENDED, SCAN_LIMIT));
        unfinished.addAll(runner.list(ExecutionStatus.RUNNING, SCAN_LIMIT));
        int cancelled = 0;
        for (ExecutionRecord record : unfinished) {
            if (!record.executionId().value().startsWith(under)) {
                continue;
            }
            JsonNode task = UpstreamCall.openTask(runner.view(record.executionId()));
            McpClientAdapter adapter = task == null ? null
                    : adapterOf(capability, task.path("namespace").asString(null));
            if (adapter != null) {
                adapter.cancelTask(task.path("taskId").asString());
                runner.cancel(record.executionId());
                cancelled++;
            }
        }
        return cancelled;
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
