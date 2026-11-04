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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * MCP tasks (SEP-2663) on the memory durability mode, driven through the protocol dispatcher.
 */
class McpTasksIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String TASKS = "io.modelcontextprotocol/tasks";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private static String clearanceCapability(String durability, String interaction) {
        return """
                ikanos: "%s"
                info:
                  display: "Departure clearance"
                  description: "Tasks integration test"
                capability:
                  aggregates:
                  - namespace: departure
                    flows:
                      clear-for-departure:
                        description: Clear a voyage for departure.
                        semantics:
                          interaction:
                            mode: %s
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
                      get-voyage:
                        description: Get a voyage.
                        outputParameters:
                          status:
                            type: string
                            value: planned
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
                      get-voyage:
                        description: Get a voyage.
                        ref: departure.get-voyage
                  - type: control
                    address: localhost
                    port: 0
                %s
                """.formatted(IKANOS, interaction, durability);
    }

    private static final String MEMORY = "    durability:\n      mode: memory\n";

    private McpServerAdapter load(String yaml) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        for (ServerAdapter adapter : new Capability(spec).getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson,
            boolean declareTasks) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        if (declareTasks) {
            meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions")
                    .putObject(TASKS);
        }
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private static final String CLEAR =
            "{\"name\":\"clear-for-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-042\"}}";

    private JsonNode awaitTerminal(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}", true)
                    .path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not end");
    }

    @Test
    void toolsCallShouldReturnATaskWhenTheFlowAndTheRequestAllowIt() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "task"));

        JsonNode created = dispatch(adapter, "tools/call", CLEAR, true).path("result");

        assertEquals("task", created.path("resultType").asString());
        assertEquals("working", created.path("status").asString());
        assertEquals(32, created.path("taskId").asString().length());
        assertTrue(created.has("createdAt"));
        assertTrue(created.has("lastUpdatedAt"));
        assertTrue(created.path("ttlMs").isNull());
        assertTrue(created.path("pollIntervalMs").asLong() > 0);
    }

    @Test
    void tasksGetShouldReturnTheResultASynchronousCallWouldReturn() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "task"));
        ObjectNode synchronous =
                (ObjectNode) dispatch(adapter, "tools/call", CLEAR, false).path("result");
        // _meta (server info) is added to every top-level response, not to an embedded result
        synchronous.remove("_meta");
        String taskId = dispatch(adapter, "tools/call", CLEAR, true).path("result")
                .path("taskId").asString();

        JsonNode task = awaitTerminal(adapter, taskId);

        assertEquals("completed", task.path("status").asString());
        assertEquals("complete", task.path("resultType").asString());
        assertEquals(synchronous, task.path("result"));
        assertFalse(task.has("pollIntervalMs"));
    }

    @Test
    void toolsCallShouldAnswerSynchronouslyWhenTheRequestDoesNotDeclareTasks() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "task"));

        JsonNode result = dispatch(adapter, "tools/call", CLEAR, false).path("result");

        assertEquals("complete", result.path("resultType").asString());
        assertFalse(result.has("taskId"));
    }

    @Test
    void toolsCallShouldAnswerInlineForAnInlineFlow() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "inline"));

        JsonNode result = dispatch(adapter, "tools/call", CLEAR, true).path("result");

        assertEquals("complete", result.path("resultType").asString());
        assertFalse(adapter.servesTasks());
    }

    @Test
    void toolsCallShouldAnswerSynchronouslyUnderTheNoneMode() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability("", "task"));

        JsonNode result = dispatch(adapter, "tools/call", CLEAR, true).path("result");

        assertEquals("complete", result.path("resultType").asString());
        assertNull(adapter.getTaskService());
    }

    @Test
    void serverDiscoverShouldAdvertiseTasksOnlyWhenTheyCanBeServed() throws Exception {
        JsonNode durable = dispatch(load(clearanceCapability(MEMORY, "task")), "server/discover",
                "{}", false).path("result").path("capabilities").path("extensions");
        JsonNode none = dispatch(load(clearanceCapability("", "task")), "server/discover", "{}",
                false).path("result").path("capabilities").path("extensions");

        assertTrue(durable.has(TASKS));
        assertFalse(none.has(TASKS));
    }

    @Test
    void tasksGetShouldRejectAnUnknownTask() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "task"));

        JsonNode response = dispatch(adapter, "tasks/get", "{\"taskId\":\"unknown\"}", true);

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void tasksCancelShouldAcknowledgeAKnownTaskAndRejectAnUnknownOne() throws Exception {
        McpServerAdapter adapter = load(clearanceCapability(MEMORY, "task"));
        String taskId = dispatch(adapter, "tools/call", CLEAR, true).path("result")
                .path("taskId").asString();

        JsonNode ack = dispatch(adapter, "tasks/cancel", "{\"taskId\":\"" + taskId + "\"}", true);
        JsonNode unknown = dispatch(adapter, "tasks/cancel", "{\"taskId\":\"unknown\"}", true);

        assertEquals("complete", ack.path("result").path("resultType").asString());
        String status = awaitTerminal(adapter, taskId).path("status").asString();
        assertTrue(status.equals("cancelled") || status.equals("completed"), status);
        assertEquals(-32602, unknown.path("error").path("code").asInt());
    }

    @Test
    void taskIdsShouldBeRandom() {
        assertNotEquals(McpTaskService.newTaskId(), McpTaskService.newTaskId());
    }

    @Test
    void loadShouldFailForADurabilityModeWhoseStoreIsNotAvailableYet() {
        String local = "    durability:\n      mode: local\n";
        assertThrows(IllegalStateException.class, () -> load(clearanceCapability(local, "task")));
    }
}
