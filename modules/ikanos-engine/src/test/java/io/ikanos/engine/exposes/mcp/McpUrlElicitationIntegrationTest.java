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
                      responses:
                        get-setup-status: '{"status":"pending"}'
                        create-project: '{"projectId":"p-1","paymentMethodId":"{{paymentMethodId}}"}'
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
}
