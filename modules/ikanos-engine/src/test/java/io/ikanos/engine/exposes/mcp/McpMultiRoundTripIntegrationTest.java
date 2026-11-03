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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Stateless multi round-trip requests (MCP {@code 2026-07-28}) for {@code elicit} steps under the
 * {@code none} durability mode, driven through the protocol dispatcher.
 */
class McpMultiRoundTripIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String KEY = "steps/confirm";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private static String capability(String extraControl, String interaction) {
        return """
                ikanos: "%s"
                info:
                  display: "Departure confirmation"
                  description: "Multi round-trip integration test"
                capability:
                  aggregates:
                  - namespace: departure
                    flows:
                      confirm-departure:
                        description: Ask the duty officer to confirm a departure.
                %s
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to confirm
                        steps:
                          confirm:
                            type: elicit
                            message: Confirm the departure?
                            schema:
                              confirmed:
                                type: boolean
                                description: Whether the departure is confirmed
                        mappings:
                        - target: confirmed
                          value: $.confirm.confirmed
                        outputParameters:
                          confirmed:
                            type: boolean
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      confirm-departure:
                        description: Confirm a departure.
                        ref: departure.confirm-departure
                  - type: control
                    address: localhost
                    port: 0
                %s
                """.formatted(IKANOS, interaction, extraControl);
    }

    private static final String CALL =
            "{\"name\":\"confirm-departure\",\"arguments\":{\"voyageId\":\"VOY-2026-043\"}}";

    private McpServerAdapter load(String yaml) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        for (ServerAdapter adapter : new Capability(spec).getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode call(McpServerAdapter adapter, String paramsJson, boolean elicitation,
            boolean tasks) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        if (elicitation) {
            capabilities.putObject("elicitation");
        }
        if (tasks) {
            capabilities.putObject("extensions").putObject("io.modelcontextprotocol/tasks");
        }
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private ObjectNode retry(String state, String responseJson) throws Exception {
        ObjectNode params = (ObjectNode) jsonMapper.readTree(CALL);
        params.put("requestState", state);
        if (responseJson != null) {
            params.putObject("inputResponses").set(KEY, jsonMapper.readTree(responseJson));
        }
        return params;
    }

    @Test
    void toolsCallShouldAskForInputWithAnElicitationRequest() throws Exception {
        JsonNode result = call(load(capability("", "")), CALL, true, false).path("result");

        assertEquals("input_required", result.path("resultType").asString());
        JsonNode request = result.path("inputRequests").path(KEY);
        assertEquals("elicitation/create", request.path("method").asString());
        assertEquals("form", request.path("params").path("mode").asString());
        assertEquals("Confirm the departure?", request.path("params").path("message").asString());
        JsonNode schema = request.path("params").path("requestedSchema");
        assertEquals("object", schema.path("type").asString());
        assertEquals("boolean", schema.path("properties").path("confirmed").path("type").asString());
        assertEquals("confirmed", schema.path("required").get(0).asString());
        assertFalse(result.path("requestState").asString().isEmpty());
    }

    @Test
    void retryShouldCompleteWithTheAcceptedAnswer() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();

        JsonNode result = call(adapter, retry(state,
                "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}").toString(), true, false)
                .path("result");

        assertEquals("complete", result.path("resultType").asString());
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.toString().contains("confirmed"), result.toString());
        assertTrue(result.toString().contains("true"), result.toString());
    }

    @Test
    void retryShouldReturnAToolErrorWhenTheCallerDeclines() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();

        JsonNode result = call(adapter, retry(state, "{\"action\":\"decline\"}").toString(), true,
                false).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
    }

    @Test
    void retryWithoutTheAnswerShouldAskAgain() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();

        JsonNode result = call(adapter, retry(state, null).toString(), true, false)
                .path("result");

        assertEquals("input_required", result.path("resultType").asString());
    }

    @Test
    void inputResponsesWithoutRequestStateShouldBeIgnored() throws Exception {
        ObjectNode params = (ObjectNode) jsonMapper.readTree(CALL);
        params.putObject("inputResponses").set(KEY,
                jsonMapper.readTree("{\"action\":\"accept\",\"content\":{\"confirmed\":true}}"));

        JsonNode result = call(load(capability("", "")), params.toString(), true, false)
                .path("result");

        assertEquals("input_required", result.path("resultType").asString());
    }

    @Test
    void tamperedRequestStateShouldBeRejected() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();
        String tampered = state.substring(0, state.length() - 2)
                + (state.endsWith("AA") ? "BB" : "AA");

        JsonNode response = call(adapter, retry(tampered,
                "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}").toString(), true, false);

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void requestStateShouldNotApplyToOtherArguments() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();
        ObjectNode other = retry(state, "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}");
        ((ObjectNode) other.path("arguments")).put("voyageId", "VOY-2026-099");

        JsonNode response = call(adapter, other.toString(), true, false);

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void toolsCallShouldRequireTheElicitationCapabilityToAsk() throws Exception {
        JsonNode response = call(load(capability("", "")), CALL, false, false);

        assertEquals(-32021, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("data").path("requiredCapabilities")
                .has("elicitation"));
    }

    @Test
    void aLongTaskToolShouldRequireTheTasksExtension() throws Exception {
        String interaction = """
                        semantics:
                          interaction:
                            mode: task
                            expectedDurationMs: 1800000
                """;
        String memory = "    durability:\n      mode: memory\n";

        JsonNode response = call(load(capability(memory, interaction)), CALL, true, false);

        assertEquals(-32021, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("data").path("requiredCapabilities")
                .path("extensions").has("io.modelcontextprotocol/tasks"));
    }

    @Test
    void aTaskWhoseFlowAsksForInputShouldFailClearlyForNow() throws Exception {
        String interaction = """
                        semantics:
                          interaction:
                            mode: task
                """;
        String memory = "    durability:\n      mode: memory\n      retention: PT1H\n";
        McpServerAdapter adapter = load(capability(memory, interaction));

        JsonNode created = call(adapter, CALL, true, true).path("result");
        assertEquals(3_600_000L, created.path("ttlMs").asLong());
        String taskId = created.path("taskId").asString();

        JsonNode task = null;
        for (int i = 0; i < 500; i++) {
            ObjectNode get = jsonMapper.createObjectNode();
            get.put("jsonrpc", "2.0");
            get.put("id", 2);
            get.put("method", "tasks/get");
            ObjectNode params = get.putObject("params");
            params.put("taskId", taskId);
            params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                    ProtocolDispatcher.MCP_PROTOCOL_VERSION);
            task = new ProtocolDispatcher(adapter).dispatch(get).responseBody().path("result");
            if (!"working".equals(task.path("status").asString())) {
                break;
            }
            Thread.sleep(10);
        }

        assertEquals("failed", task.path("status").asString(), String.valueOf(task));
        assertTrue(task.path("error").path("message").asString().contains("input"));
    }
}
