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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.fake.FakeClientAdapter;
import io.ikanos.engine.consumes.fake.FakeClientSpec;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A URL mode {@code elicit} step (issue 1151): the caller is sent to a payment page, its
 * {@code accept} only means consent, and the step's {@code check} decides on each retry whether
 * the payment method was added. The consumed operations are the test {@code fake} adapter's
 * canned responses, changed between rounds to play the page. Under {@code none} the call replays
 * with the sealed state; under {@code memory} it resumes a journal.
 */
class McpUrlElicitationIntegrationTest {

    private static final String SEALING_KEY = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String CALL = "{\"name\":\"create-paid-project\",\"arguments\":{}}";
    private static final String KEY = "steps/payment";
    private static final String PENDING = "{\"status\":\"pending\"}";
    private static final String DONE = "{\"status\":\"done\",\"paymentMethodId\":\"pm_123\"}";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();
    private Capability capability;

    private static String capability(String mode) {
        return """
                ikanos: "%s"
                info:
                  display: "Paid projects"
                  description: "URL mode elicitation integration test"
                capability:
                  consumes:
                    - type: fake
                      namespace: payments
                      operations:
                        get-setup-status:
                          outputParameters: []
                        create-project:
                          outputParameters: []
                        get-broken-status:
                          outputParameters: []
                      responses:
                        get-setup-status: '{"status":"pending"}'
                        create-project: '{"projectId":"p-1","paymentMethodId":"{{paymentMethodId}}"}'
                        get-broken-status: '{"error":"unavailable"}'
                      statuses:
                        get-broken-status: 500
                  aggregates:
                  - namespace: billing
                    flows:
                      create-paid-project:
                        description: Create a project once the customer has added a payment method.
                        steps:
                          payment:
                            type: elicit
                            mode: url
                            message: Add a payment method to create a paid project.
                            url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                            check:
                              type: call
                              call: payments.get-setup-status
                              with:
                                ref: "{{elicitation.id}}"
                            outputParameters:
                              paymentMethodId:
                                type: string
                          create:
                            type: call
                            call: payments.create-project
                            with:
                              paymentMethodId: "{{payment.paymentMethodId}}"
                      broken-check:
                        description: A payment step whose check fails upstream.
                        steps:
                          payment:
                            type: elicit
                            mode: url
                            message: Add a payment method.
                            url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                            check:
                              type: call
                              call: payments.get-broken-status
                              with:
                                ref: "{{elicitation.id}}"
                            outputParameters:
                              paymentMethodId:
                                type: string
                      ask-for-ref:
                        description: A flow that asks its caller, called as a check.
                        steps:
                          confirm:
                            type: elicit
                            message: Confirm the reference?
                            fields:
                              confirmed:
                                type: boolean
                      asking-check:
                        description: A payment step whose check asks its caller for input.
                        steps:
                          payment:
                            type: elicit
                            mode: url
                            message: Add a payment method.
                            url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                            check:
                              type: call
                              call: billing.ask-for-ref
                              with:
                                ref: "{{elicitation.id}}"
                            outputParameters:
                              paymentMethodId:
                                type: string
                      confirm-only:
                        description: Ask the caller to confirm in a form.
                        steps:
                          confirm:
                            type: elicit
                            message: Create the project?
                            fields:
                              confirmed:
                                type: boolean
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: billing-mcp
                    description: Billing operations.
                    tools:
                      create-paid-project:
                        description: Create a paid project, once a payment method is added.
                        ref: billing.create-paid-project
                      broken-check:
                        description: A payment step whose check fails upstream.
                        ref: billing.broken-check
                      asking-check:
                        description: A payment step whose check asks its caller for input.
                        ref: billing.asking-check
                      confirm-only:
                        description: Ask the caller to confirm in a form.
                        ref: billing.confirm-only
                    resources:
                      payment-page:
                        uri: billing://payment
                        description: The payment method, once the customer added one.
                        steps:
                          payment:
                            type: elicit
                            mode: url
                            message: Add a payment method.
                            url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                            check:
                              type: call
                              call: payments.get-setup-status
                              with:
                                ref: "{{elicitation.id}}"
                            outputParameters:
                              paymentMethodId:
                                type: string
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: %s
                      sealingKey: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), mode, SEALING_KEY);
    }

    @BeforeEach
    void clearCalls() {
        FakeClientAdapter.CALLS.clear();
    }

    private McpServerAdapter load(String mode) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(capability(mode), IkanosSpec.class);
        capability = new Capability(spec);
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    /** Plays the payment page: what the check reads from now on. */
    private void setupStatus(String body) {
        for (ClientAdapter adapter : capability.getClientAdapters()) {
            if (adapter.getSpec() instanceof FakeClientSpec fake) {
                fake.getResponses().put("get-setup-status", body);
            }
        }
    }

    private ObjectNode call(McpServerAdapter adapter, String paramsJson, String elicitation)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities")
                .set("elicitation", jsonMapper.readTree(elicitation));
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private JsonNode urlCall(McpServerAdapter adapter, String paramsJson) throws Exception {
        return call(adapter, paramsJson, "{\"url\":{}}").path("result");
    }

    /** A retry of the call, accepting the question asked under {@code key}. */
    private String accept(JsonNode asked, String key) {
        ObjectNode retry = (ObjectNode) jsonMapper.readTree(CALL);
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").putObject(key).put("action", "accept");
        return retry.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void toolsCallShouldAskInUrlModeWithTheQuestionIdInTheUrl(String mode) throws Exception {
        JsonNode first = urlCall(load(mode), CALL);

        JsonNode params = first.path("inputRequests").path(KEY).path("params");
        assertEquals("input_required", first.path("resultType").asString(), first.toString());
        assertEquals("url", params.path("mode").asString(), params.toString());
        assertTrue(params.path("url").asString()
                .matches("https://billing\\.example\\.com/setup\\?ref=[A-Za-z0-9_-]{32}"),
                params.toString());
        assertFalse(params.has("requestedSchema"), params.toString());
        assertFalse(params.has("elicitationId"), params.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void toolsCallShouldRefuseAClientThatDeclaresFormOnly(String mode) throws Exception {
        JsonNode error = call(load(mode), CALL, "{}").path("error");

        assertEquals(-32021, error.path("code").asInt(), error.toString());
        assertTrue(error.path("data").path("requiredCapabilities").path("elicitation")
                .has("url"), error.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void toolsCallShouldAskAgainWithTheSameUrlWhileTheCheckFindsNoValue(String mode)
            throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = urlCall(adapter, CALL);

        JsonNode second = urlCall(adapter, accept(first, KEY));

        assertEquals("input_required", second.path("resultType").asString(), second.toString());
        assertEquals(first.path("inputRequests").path(KEY).path("params").path("url"),
                second.path("inputRequests").path(KEY + "@2").path("params").path("url"),
                second.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void toolsCallShouldCompleteWithTheCheckOutputOnceThePageIsDone(String mode)
            throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = urlCall(adapter, CALL);
        JsonNode second = urlCall(adapter, accept(first, KEY));
        setupStatus(DONE);

        JsonNode done = urlCall(adapter, accept(second, KEY + "@2"));

        assertEquals("complete", done.path("resultType").asString("complete"), done.toString());
        assertTrue(done.toString().contains("pm_123"), done.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void requestStateShouldNotGrowWhileTheCheckStaysPending(String mode) throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode asked = urlCall(adapter, CALL);
        String key = KEY;
        int third = 0;
        for (int visit = 2; visit <= 12; visit++) {
            asked = urlCall(adapter, accept(asked, key));
            key = KEY + "@" + visit;
            assertTrue(asked.path("inputRequests").has(key), asked.toString());
            if (visit == 3) {
                third = asked.path("requestState").asString().length();
            }
        }
        // Only the latest visit's answer is carried: a few more digits in the key at most.
        assertTrue(asked.path("requestState").asString().length() <= third + 8,
                third + " then " + asked.path("requestState").asString().length());
        setupStatus(DONE);

        JsonNode done = urlCall(adapter, accept(asked, key));

        assertEquals("complete", done.path("resultType").asString("complete"), done.toString());
        assertTrue(done.toString().contains("pm_123"), done.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void checkShouldReceiveTheIdTheUrlCarries(String mode) throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = urlCall(adapter, CALL);
        setupStatus(DONE);

        urlCall(adapter, accept(first, KEY));

        String url = first.path("inputRequests").path(KEY).path("params").path("url").asString();
        String id = url.substring(url.indexOf("ref=") + "ref=".length());
        List<Map<String, Object>> checks = FakeClientAdapter.CALLS.stream()
                .filter(params -> params.containsKey("ref")).toList();
        assertEquals(id, checks.get(checks.size() - 1).get("ref"), checks.toString());
    }

    /** A call of {@code tool} with no arguments. */
    private static String callOf(String tool) {
        return "{\"name\":\"" + tool + "\",\"arguments\":{}}";
    }

    /** A retry of the call of {@code tool}, answering {@code key} with {@code action}. */
    private String answer(String tool, JsonNode asked, String key, String action) {
        ObjectNode retry = (ObjectNode) jsonMapper.readTree(callOf(tool));
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").putObject(key).put("action", action);
        return retry.toString();
    }

    /** Asserts the response ends the call with a failure, not another question. */
    private static void assertFailed(JsonNode response) {
        JsonNode result = response.path("result");
        assertFalse("input_required".equals(result.path("resultType").asString()),
                response.toString());
        assertTrue(response.has("error") || result.path("isError").asBoolean(false),
                response.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decline", "cancel"})
    void aDeclinedOrCancelledUrlQuestionShouldFailTheStep(String action) throws Exception {
        for (String mode : List.of("none", "memory")) {
            McpServerAdapter adapter = load(mode);
            JsonNode first = urlCall(adapter, CALL);

            ObjectNode answered = call(adapter,
                    answer("create-paid-project", first, KEY, action), "{\"url\":{}}");

            assertFailed(answered);
            assertTrue(FakeClientAdapter.CALLS.stream().noneMatch(p -> p.containsKey("ref")),
                    mode + ": the check never runs on a " + action);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"decline", "cancel"})
    void aDeclinedOrCancelledReAskShouldFailTheStep(String action) throws Exception {
        for (String mode : List.of("none", "memory")) {
            McpServerAdapter adapter = load(mode);
            JsonNode first = urlCall(adapter, CALL);
            JsonNode second = urlCall(adapter, accept(first, KEY));
            setupStatus(DONE);

            ObjectNode answered = call(adapter,
                    answer("create-paid-project", second, KEY + "@2", action), "{\"url\":{}}");

            assertFailed(answered);
            assertFalse(answered.toString().contains("pm_123"), mode + ": " + answered);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void aCheckThatFailsUpstreamShouldFailTheStep(String mode) throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = urlCall(adapter, callOf("broken-check"));
        assertEquals("input_required", first.path("resultType").asString(), first.toString());

        assertFailed(call(adapter, answer("broken-check", first, KEY, "accept"),
                "{\"url\":{}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void aCheckThatAsksItsCallerForInputShouldBeRefused(String mode) throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = call(adapter, callOf("asking-check"), "{\"form\":{},\"url\":{}}")
                .path("result");
        assertEquals("input_required", first.path("resultType").asString(), first.toString());

        ObjectNode answered = call(adapter, answer("asking-check", first, KEY, "accept"),
                "{\"form\":{},\"url\":{}}");

        assertFailed(answered);
        assertFalse(answered.path("result").path("inputRequests").has("steps/confirm"),
                answered.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void aClientThatDeclaresUrlOnlyShouldBeRefusedAFormQuestionNamingForm(String mode)
            throws Exception {
        JsonNode error = call(load(mode), callOf("confirm-only"), "{\"url\":{}}").path("error");

        assertEquals(-32021, error.path("code").asInt(), error.toString());
        JsonNode elicitation = error.path("data").path("requiredCapabilities")
                .path("elicitation");
        assertTrue(elicitation.has("form"), error.toString());
        assertFalse(elicitation.has("url"), error.toString());
    }

    private ObjectNode read(McpServerAdapter adapter, String paramsJson, String elicitation)
            throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "resources/read");
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities")
                .set("elicitation", jsonMapper.readTree(elicitation));
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    @org.junit.jupiter.api.Test
    void resourcesReadShouldRefuseAFormOnlyClientBeforeAnyStepRunsInMemoryMode()
            throws Exception {
        JsonNode error = read(load("memory"), "{\"uri\":\"billing://payment\"}", "{}")
                .path("error");

        assertEquals(-32021, error.path("code").asInt(), error.toString());
        assertTrue(error.path("data").path("requiredCapabilities").path("elicitation")
                .has("url"), error.toString());
        assertTrue(FakeClientAdapter.CALLS.isEmpty(), FakeClientAdapter.CALLS.toString());
    }

    @org.junit.jupiter.api.Test
    void resourcesReadShouldAskInUrlModeAndCompleteInMemoryMode() throws Exception {
        McpServerAdapter adapter = load("memory");
        String uri = "{\"uri\":\"billing://payment\"}";
        JsonNode first = read(adapter, uri, "{\"url\":{}}").path("result");
        assertEquals("url", first.path("inputRequests").path(KEY).path("params").path("mode")
                .asString(), first.toString());
        setupStatus(DONE);

        ObjectNode retry = (ObjectNode) jsonMapper.readTree(uri);
        retry.put("requestState", first.path("requestState").asString());
        retry.putObject("inputResponses").putObject(KEY).put("action", "accept");
        JsonNode done = read(adapter, retry.toString(), "{\"url\":{}}").path("result");

        assertEquals("complete", done.path("resultType").asString("complete"), done.toString());
        // The resource maps no output: it is done once its check found the value.
        assertTrue(FakeClientAdapter.CALLS.stream().anyMatch(p -> p.containsKey("ref")),
                FakeClientAdapter.CALLS.toString());
    }
}
