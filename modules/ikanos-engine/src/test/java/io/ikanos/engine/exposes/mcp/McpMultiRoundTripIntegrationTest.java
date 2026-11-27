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
                  title: "Departure confirmation"
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
                            fields:
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
                    resources:
                      departure-note:
                        uri: shipyard://departures/note
                        description: A departure note, confirmed by the duty officer.
                        steps:
                          confirm:
                            type: elicit
                            message: Publish the departure note?
                            fields:
                              confirmed:
                                type: boolean
                                description: Whether the note is published
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
        return send(adapter, "tools/call", paramsJson, elicitation, tasks);
    }

    private ObjectNode send(McpServerAdapter adapter, String method, String paramsJson,
            boolean elicitation, boolean tasks) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
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

    private static final String READ = "{\"uri\":\"shipyard://departures/note\"}";

    @Test
    void resourcesReadShouldAskForInputAndCompleteOnRetry() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        JsonNode asked = send(adapter, "resources/read", READ, true, false).path("result");

        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        assertEquals("Publish the departure note?", asked.path("inputRequests").path(KEY)
                .path("params").path("message").asString());

        ObjectNode retry = (ObjectNode) jsonMapper.readTree(READ);
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set(KEY,
                jsonMapper.readTree("{\"action\":\"accept\",\"content\":{\"confirmed\":true}}"));
        JsonNode result = send(adapter, "resources/read", retry.toString(), true, false)
                .path("result");

        assertEquals("complete", result.path("resultType").asString(), result.toString());
        assertEquals("shipyard://departures/note",
                result.path("contents").get(0).path("uri").asString());
    }

    @Test
    void anInteractiveResourcesReadShouldNeverBeCacheable() throws Exception {
        // the answers are not in the URI, so cacheScope auto must not make the read shareable
        McpServerAdapter adapter = load(capability("", "").replace(
                "    namespace: shipyard-ops\n",
                "    namespace: shipyard-ops\n    ttlMs: 60000\n    cacheScope: auto\n"));
        JsonNode asked = send(adapter, "resources/read", READ, true, false).path("result");

        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        assertEquals(0, asked.path("ttlMs").asInt(-1), asked.toString());
        assertEquals("private", asked.path("cacheScope").asString(), asked.toString());

        ObjectNode retry = (ObjectNode) jsonMapper.readTree(READ);
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set(KEY,
                jsonMapper.readTree("{\"action\":\"accept\",\"content\":{\"confirmed\":true}}"));
        JsonNode result = send(adapter, "resources/read", retry.toString(), true, false)
                .path("result");

        assertEquals("complete", result.path("resultType").asString(), result.toString());
        assertEquals(0, result.path("ttlMs").asInt(-1), result.toString());
        assertEquals("private", result.path("cacheScope").asString(), result.toString());

        // the same adapter still shares its lists, which no caller's answer shapes
        JsonNode list = send(adapter, "resources/list", "{}", true, false).path("result");
        assertEquals(60000, list.path("ttlMs").asInt(-1), list.toString());
        assertEquals("public", list.path("cacheScope").asString(), list.toString());
    }

    @Test
    void aToolsCallStateShouldNotOpenAResourcesRead() throws Exception {
        McpServerAdapter adapter = load(capability("", ""));
        String state = call(adapter, CALL, true, false).path("result").path("requestState")
                .asString();
        ObjectNode read = (ObjectNode) jsonMapper.readTree(READ);
        read.put("requestState", state);

        JsonNode response = send(adapter, "resources/read", read.toString(), true, false);

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void resourcesReadShouldRequireTheElicitationCapabilityToAsk() throws Exception {
        JsonNode response = send(load(capability("", "")), "resources/read", READ, false, false);

        assertEquals(-32021, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("data").path("requiredCapabilities")
                .has("elicitation"));
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

    /** A flow calling two flows whose elicit steps share the name {@code confirm}. */
    private static final String NESTED = """
            ikanos: "%s"
            info:
              title: "Departure clearance"
              description: "Nested elicit integration test"
            capability:
              aggregates:
              - namespace: departure
                flows:
                  confirm-crew:
                    description: Ask the duty officer to confirm the crew list.
                    steps:
                      confirm:
                        type: elicit
                        message: Confirm the crew list?
                        fields:
                          confirmed:
                            type: boolean
                            description: Whether the crew list is confirmed
                  confirm-cargo:
                    description: Ask the duty officer to confirm the cargo manifest.
                    steps:
                      confirm:
                        type: elicit
                        message: Confirm the cargo manifest?
                        fields:
                          confirmed:
                            type: boolean
                            description: Whether the cargo manifest is confirmed
                  clear-departure:
                    description: Clear a departure once crew and cargo are confirmed.
                    steps:
                      crew:
                        type: call
                        call: departure.confirm-crew
                      cargo:
                        type: call
                        call: departure.confirm-cargo
              exposes:
              - type: mcp
                address: localhost
                port: 0
                namespace: shipyard-ops
                description: Shipyard operations.
                tools:
                  clear-departure:
                    description: Clear a departure.
                    ref: departure.clear-departure
            """.formatted(IKANOS);

    private static final String CLEAR = "{\"name\":\"clear-departure\",\"arguments\":{}}";
    private static final String ACCEPT = "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}";

    @Test
    void nestedElicitStepsWithTheSameNameShouldEachBeAsked() throws Exception {
        McpServerAdapter adapter = load(NESTED);

        JsonNode first = call(adapter, CLEAR, true, false).path("result");
        assertEquals("input_required", first.path("resultType").asString(), first.toString());
        JsonNode crew = first.path("inputRequests");
        assertEquals(1, crew.size(), crew.toString());
        String crewKey = crew.propertyNames().iterator().next();
        assertEquals("steps/crew/steps/confirm", crewKey);
        assertEquals("Confirm the crew list?",
                crew.path(crewKey).path("params").path("message").asString());

        // Answering the crew confirmation must not answer the cargo one: it is asked next.
        ObjectNode retry = (ObjectNode) jsonMapper.readTree(CLEAR);
        retry.put("requestState", first.path("requestState").asString());
        retry.putObject("inputResponses").set(crewKey, jsonMapper.readTree(ACCEPT));
        JsonNode second = call(adapter, retry.toString(), true, false).path("result");
        assertEquals("input_required", second.path("resultType").asString(), second.toString());
        JsonNode cargo = second.path("inputRequests");
        assertEquals(1, cargo.size(), cargo.toString());
        String cargoKey = cargo.propertyNames().iterator().next();
        assertEquals("steps/cargo/steps/confirm", cargoKey);
        assertEquals("Confirm the cargo manifest?",
                cargo.path(cargoKey).path("params").path("message").asString());

        ObjectNode last = (ObjectNode) jsonMapper.readTree(CLEAR);
        last.put("requestState", second.path("requestState").asString());
        last.putObject("inputResponses").set(cargoKey, jsonMapper.readTree(ACCEPT));
        JsonNode done = call(adapter, last.toString(), true, false).path("result");
        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertFalse(done.path("isError").asBoolean(), done.toString());
    }

    /**
     * A flow whose second question names the voyage, an input, and the captain, the answer to
     * the first question.
     */
    private static String templated(String message) {
        return """
                ikanos: "%s"
                info:
                  title: "Departure confirmation"
                  description: "Templated elicit message integration test"
                capability:
                  aggregates:
                  - namespace: departure
                    flows:
                      confirm-departure:
                        description: Ask the duty officer to confirm a departure.
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to confirm
                        steps:
                          captain:
                            type: elicit
                            message: Who is the captain?
                            fields:
                              name:
                                type: string
                                description: The captain's name
                          confirm:
                            type: elicit
                            message: "%s"
                            fields:
                              confirmed:
                                type: string
                                enum: [CONFIRM]
                                description: Choose CONFIRM to confirm
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
                """.formatted(IKANOS, message);
    }

    /** Answers the captain question with {@code Ada} and returns the next round's result. */
    private JsonNode answerTheCaptain(McpServerAdapter adapter) throws Exception {
        JsonNode first = call(adapter, CALL, true, false).path("result");
        assertEquals("input_required", first.path("resultType").asString(), first.toString());
        ObjectNode retry = (ObjectNode) jsonMapper.readTree(CALL);
        retry.put("requestState", first.path("requestState").asString());
        retry.putObject("inputResponses").set("steps/captain",
                jsonMapper.readTree("{\"action\":\"accept\",\"content\":{\"name\":\"Ada\"}}"));
        return call(adapter, retry.toString(), true, false);
    }

    @Test
    void elicitMessageShouldBeRenderedFromTheInputsAndTheEarlierSteps() throws Exception {
        McpServerAdapter adapter = load(templated(
                "Confirm the departure of {{voyageId}} under captain {{captain.name}}?"));

        JsonNode second = answerTheCaptain(adapter).path("result");

        assertEquals("input_required", second.path("resultType").asString(), second.toString());
        assertEquals("Confirm the departure of VOY-2026-043 under captain Ada?",
                second.path("inputRequests").path(KEY).path("params").path("message")
                        .asString());
    }

    @Test
    void elicitMessageReadingAValueNothingProvidesShouldFailInsteadOfAsking() throws Exception {
        McpServerAdapter adapter = load(templated("Confirm the departure of {{vessel.name}}?"));

        JsonNode response = answerTheCaptain(adapter);

        // The caller gets a tool error, not a question with a blank in it; the reason, a
        // capability mistake, goes to the server log rather than to the caller.
        JsonNode result = response.path("result");
        assertEquals("complete", result.path("resultType").asString(), response.toString());
        assertTrue(result.path("isError").asBoolean(), response.toString());
    }
}
