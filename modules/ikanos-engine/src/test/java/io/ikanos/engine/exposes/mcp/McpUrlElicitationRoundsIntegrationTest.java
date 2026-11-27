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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.fake.FakeClientSpec;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A URL mode {@code elicit} step followed by a form one (issue 1151), inline and as a task. Once
 * the check found the payment method, a later round restores the step's output instead of
 * running the check again: the payment page going back to pending in between neither asks the
 * caller again nor changes what later steps read.
 */
class McpUrlElicitationRoundsIntegrationTest {

    private static final String SEALING_KEY = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String PAYMENT = "steps/payment";
    private static final String CONFIRM = "steps/confirm";
    private static final String PENDING = "{\"status\":\"pending\"}";
    private static final String DONE = "{\"status\":\"done\",\"paymentMethodId\":\"pm_123\"}";
    private static final String ACCEPT = "{\"action\":\"accept\"}";
    private static final String CONFIRMED =
            "{\"action\":\"accept\",\"content\":{\"confirmed\":true}}";
    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final String BOTH = "{\"form\":{},\"url\":{}}";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();
    private Capability capability;

    private static String capability(String mode) {
        return """
                ikanos: "%s"
                info:
                  display: "Paid projects"
                  description: "URL mode elicitation over several rounds"
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
                      pay-then-confirm:
                        description: Add a payment method, confirm, then create the project.
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
                          confirm:
                            type: elicit
                            message: Create the paid project?
                            fields:
                              confirmed:
                                type: boolean
                          create:
                            type: call
                            call: payments.create-project
                            with:
                              paymentMethodId: "{{payment.paymentMethodId}}"
                      pay-then-confirm-task:
                        description: The same, as a task.
                        semantics:
                          interaction:
                            mode: task
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
                          confirm:
                            type: elicit
                            message: Create the paid project?
                            fields:
                              confirmed:
                                type: boolean
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
                      pay-then-confirm:
                        description: Create a paid project, once paid and confirmed.
                        ref: billing.pay-then-confirm
                      pay-then-confirm-task:
                        description: Create a paid project as a task, once paid and confirmed.
                        ref: billing.pay-then-confirm-task
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: %s
                      sealingKey: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), mode, SEALING_KEY);
    }

    @AfterEach
    void stop() throws Exception {
        if (capability != null) {
            capability.stop();
        }
    }

    private McpServerAdapter load(String mode) throws Exception {
        capability = new Capability(yamlMapper.readValue(capability(mode), IkanosSpec.class));
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

    private ObjectNode dispatch(McpServerAdapter adapter, String method, String paramsJson,
            String elicitation, boolean tasks) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.set("elicitation", jsonMapper.readTree(elicitation));
        if (tasks) {
            capabilities.putObject("extensions").putObject(TASKS);
        }
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    /** A retry of the inline call, answering {@code key} with {@code answer}. */
    private String retry(JsonNode asked, String key, String answer) {
        ObjectNode retry = jsonMapper.createObjectNode();
        retry.put("name", "pay-then-confirm");
        retry.putObject("arguments");
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set(key, jsonMapper.readTree(answer));
        return retry.toString();
    }

    private JsonNode inline(McpServerAdapter adapter, String paramsJson) throws Exception {
        return dispatch(adapter, "tools/call", paramsJson, BOTH, false).path("result");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void aFinishedUrlStepShouldBeRestoredWhenItsCheckChangesItsAnswer(String mode)
            throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode asked = inline(adapter,
                "{\"name\":\"pay-then-confirm\",\"arguments\":{}}");
        setupStatus(DONE);
        JsonNode confirm = inline(adapter, retry(asked, PAYMENT, ACCEPT));
        assertTrue(confirm.path("inputRequests").has(CONFIRM), confirm.toString());
        setupStatus(PENDING);

        JsonNode done = inline(adapter, retry(confirm, CONFIRM, CONFIRMED));

        assertEquals("complete", done.path("resultType").asString("complete"), done.toString());
        assertTrue(done.toString().contains("pm_123"), done.toString());
    }

    private JsonNode task(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = dispatch(adapter, "tasks/get", "{\"taskId\":\"" + taskId + "\"}",
                    BOTH, true).path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    private void update(McpServerAdapter adapter, String taskId, String key, String answer)
            throws Exception {
        dispatch(adapter, "tasks/update", "{\"taskId\":\"" + taskId
                + "\",\"inputResponses\":{\"" + key + "\":" + answer + "}}", BOTH, true);
    }

    private String createTask(McpServerAdapter adapter) throws Exception {
        JsonNode created = dispatch(adapter, "tools/call",
                "{\"name\":\"pay-then-confirm-task\",\"arguments\":{}}", BOTH, true)
                .path("result");
        assertEquals("task", created.path("resultType").asString(), created.toString());
        return created.path("taskId").asString();
    }

    @Test
    void aTaskShouldRefuseAClientThatDeclaresFormOnly() throws Exception {
        JsonNode error = dispatch(load("memory"), "tools/call",
                "{\"name\":\"pay-then-confirm-task\",\"arguments\":{}}", "{}", true)
                .path("error");

        assertEquals(-32021, error.path("code").asInt(), error.toString());
        assertTrue(error.path("data").path("requiredCapabilities").path("elicitation")
                .has("url"), error.toString());
    }

    @Test
    void aTaskShouldAskInUrlModeAndAskAgainWhileTheCheckFindsNoValue() throws Exception {
        McpServerAdapter adapter = load("memory");
        String taskId = createTask(adapter);
        JsonNode first = task(adapter, taskId);
        assertEquals("url", first.path("inputRequests").path(PAYMENT).path("params")
                .path("mode").asString(), first.toString());

        update(adapter, taskId, PAYMENT, ACCEPT);
        JsonNode again = task(adapter, taskId);

        assertEquals("input_required", again.path("status").asString(), again.toString());
        assertEquals(first.path("inputRequests").path(PAYMENT).path("params").path("url"),
                again.path("inputRequests").path(PAYMENT + "@2").path("params").path("url"),
                again.toString());
    }

    @Test
    void aTaskShouldCompleteWithTheOutputItsCheckFoundFirst() throws Exception {
        McpServerAdapter adapter = load("memory");
        String taskId = createTask(adapter);
        task(adapter, taskId);
        setupStatus(DONE);
        update(adapter, taskId, PAYMENT, ACCEPT);
        JsonNode confirm = task(adapter, taskId);
        assertTrue(confirm.path("inputRequests").has(CONFIRM), confirm.toString());
        setupStatus(PENDING);

        update(adapter, taskId, CONFIRM, CONFIRMED);
        JsonNode done = task(adapter, taskId);

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.path("result").toString().contains("pm_123"), done.toString());
    }
}
