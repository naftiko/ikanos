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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.Capability;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP tasks in the {@code local} mode across a restart: two capabilities loaded one after the
 * other on the same H2 file stand for the process before and after it.
 */
public class McpTaskRestartIntegrationTest {

    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final String ENTRY = "mcp:shipyard-ops/clear-for-departure";
    private static final String CLEAR =
            "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-042\"}}";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    @TempDir
    Path directory;

    private String capability(String description) {
        String path = directory.resolve("durable").toString().replace('\\', '/');
        return """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "%s"
                capability:
                  aggregates:
                  - namespace: departure
                    flows:
                      clear-for-departure:
                        description: Clear a voyage for departure.
                        semantics:
                          interaction:
                            mode: task
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to clear
                        outputParameters:
                          voyageId:
                            type: string
                            value: "{{voyageId}}"
                          status:
                            type: string
                            value: cleared
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      clear-for-departure:
                        description: Clear a voyage for departure.
                        ref: departure.clear-for-departure
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: local
                      store:
                        path: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), description, path);
    }

    private Capability load(String description) throws Exception {
        return new Capability(yamlMapper.readValue(capability(description), IkanosSpec.class));
    }

    private static McpServerAdapter mcp(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                .putObject(TASKS);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private JsonNode awaitTerminal(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}")
                    .path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not end");
    }

    /** Records a task as a process that died right after creating it would have left it. */
    private void startWithoutRunning(Capability capability, String taskId) throws Exception {
        capability.getDurability().runner().start(
                ExecutionRequest.of(ExecutionId.random(),
                        jsonMapper.readTree("{\"voyageId\":\"VOY-2026-042\"}")),
                ENTRY, Map.of(McpTaskService.TASK_ID, taskId, McpTaskService.PRINCIPAL,
                        McpTaskService.digest(McpCallContext.principal())));
    }

    @Test
    void tasksGetShouldAnswerForATaskCreatedBeforeARestart() throws Exception {
        Capability before = load("before");
        String taskId;
        JsonNode result;
        try {
            McpServerAdapter adapter = mcp(before);
            taskId = dispatch(adapter, "tools/call", CLEAR).path("result").path("taskId")
                    .asString();
            result = awaitTerminal(adapter, taskId).path("result");
        } finally {
            before.getDurability().close();
        }

        Capability after = load("before");
        try {
            JsonNode task = dispatch(mcp(after), "tasks/get", "{\"taskId\":\"" + taskId + "\"}")
                    .path("result");
            assertEquals("completed", task.path("status").asString());
            assertEquals(result, task.path("result"));
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void resumeShouldFinishATaskCutShortAfterItsCreation() throws Exception {
        Capability before = load("before");
        try {
            startWithoutRunning(before, "task-cut-short");
        } finally {
            before.getDurability().close();
        }

        Capability after = load("before");
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(1, adapter.resumeTasks());
            JsonNode task = awaitTerminal(adapter, "task-cut-short");

            assertEquals("completed", task.path("status").asString());
            assertTrue(task.path("result").toString().contains("VOY-2026-042"),
                    "The resumed call gets the arguments recorded at creation: " + task);
            assertEquals(0, adapter.resumeTasks(), "A finished task is not resumed twice");
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void resumeShouldFailATaskStartedByAnotherVersionOfTheCapability() throws Exception {
        Capability before = load("before");
        try {
            startWithoutRunning(before, "task-old-spec");
        } finally {
            before.getDurability().close();
        }

        Capability after = load("after an edit");
        try {
            McpServerAdapter adapter = mcp(after);
            assertEquals(0, adapter.resumeTasks());
            JsonNode task = dispatch(adapter, "tasks/get", "{\"taskId\":\"task-old-spec\"}")
                    .path("result");

            assertEquals("failed", task.path("status").asString());
            assertTrue(task.path("error").path("message").asString().contains("changed"),
                    task.toString());
        } finally {
            after.getDurability().close();
        }
    }

    @Test
    void tasksOfAnotherPrincipalShouldStayUnknown() throws Exception {
        Capability capability = load("before");
        try {
            capability.getDurability().runner().start(
                    ExecutionRequest.of(ExecutionId.random(), null), ENTRY,
                    Map.of(McpTaskService.TASK_ID, "task-of-someone-else",
                            McpTaskService.PRINCIPAL, McpTaskService.digest("someone-else")));
            JsonNode response = dispatch(mcp(capability), "tasks/get",
                    "{\"taskId\":\"task-of-someone-else\"}");

            assertEquals(-32602, response.path("error").path("code").asInt());
        } finally {
            capability.getDurability().close();
        }
    }
}
