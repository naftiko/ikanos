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

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Consumed MCP tasks and composite elicitation (E3e): a consumer capability calls an upstream
 * Ikanos capability whose tools answer with a task or ask for input; the consumer waits on the
 * task, and passes the upstream's questions through to its own caller, whose answers complete the
 * upstream call, without filing the declaration twice.
 */
public class McpCompositeElicitationIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String TASKS = "io.modelcontextprotocol/tasks";
    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();
    private final ObjectMapper json = IkanosMappers.json();
    private final AtomicInteger filings = new AtomicInteger();

    @TempDir
    Path directory;

    private Component port;
    private Capability upstream;
    private Capability consumer;
    private int portPort;
    private int upstreamPort;

    @BeforeEach
    void startUpstream() throws Exception {
        portPort = freePort();
        upstreamPort = freePort();
        port = new Component();
        port.getServers().add(Protocol.HTTP, portPort);
        port.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/declarations", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        int n = filings.incrementAndGet();
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity("{\"declarationId\":\"DEC-" + n + "\"}",
                                MediaType.APPLICATION_JSON);
                    }
                });
                return router;
            }
        });
        port.start();
        upstream = new Capability(YAML.readValue(upstreamYaml(), IkanosSpec.class));
        upstream.start();
    }

    @AfterEach
    void stopAll() throws Exception {
        if (consumer != null) {
            consumer.stop();
        }
        upstream.stop();
        port.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String upstreamYaml() {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: port
                      baseUri: "http://localhost:%d"
                      resources:
                        declarations:
                          path: /declarations
                          operations:
                            file-declaration:
                              method: POST
                  aggregates:
                    - namespace: customs
                      flows:
                        ask-confirm:
                          description: Ask the officer to confirm the export.
                          steps:
                            confirm:
                              type: elicit
                              message: Confirm the export?
                              fields:
                                confirmed: { type: boolean, description: Whether it is confirmed }
                          mappings:
                            - { target: confirmed, value: $.confirm.confirmed }
                          outputParameters:
                            confirmed: { type: boolean }
                        file-slowly:
                          description: File a declaration, as a task.
                          semantics:
                            interaction:
                              mode: task
                          steps:
                            file:
                              type: call
                              call: port.file-declaration
                          mappings:
                            - { target: declarationId, value: $.file.declarationId }
                          outputParameters:
                            declarationId: { type: string }
                        file-then-ask:
                          description: File a declaration, then ask to pay the fee, as a task.
                          semantics:
                            interaction:
                              mode: task
                          steps:
                            file:
                              type: call
                              call: port.file-declaration
                            confirm:
                              type: elicit
                              message: Pay the inspection fee?
                              fields:
                                accepted: { type: boolean, description: Whether the fee is accepted }
                          mappings:
                            - { target: accepted, value: $.confirm.accepted }
                          outputParameters:
                            accepted: { type: boolean }
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: %d
                      namespace: customs-mcp
                      tools:
                        ask-confirm: { description: Ask to confirm, ref: customs.ask-confirm }
                        file-slowly: { description: File slowly, ref: customs.file-slowly }
                        file-then-ask: { description: File then ask, ref: customs.file-then-ask }
                    - type: control
                      address: localhost
                      port: 0
                      durability:
                        mode: memory
                """.formatted(IKANOS, portPort, upstreamPort);
    }

    private String consumerYaml(String durability, boolean declared) {
        String mayElicit = declared ? "mayElicit: true" : "mayElicit: false";
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: mcp
                      namespace: customs
                      endpoint: http://localhost:%d/
                      discovery: off
                      tools:
                        ask-confirm: { description: Ask to confirm, %s }
                        file-slowly: { description: File slowly }
                        file-then-ask: { description: File then ask, %s }
                  aggregates:
                    - namespace: ops
                      flows:
                        confirm-up:
                          description: Ask through the upstream.
                          steps:
                            ask:
                              type: call
                              call: customs.ask-confirm
                          mappings:
                            - { target: confirmed, value: $.ask.confirmed }
                          outputParameters:
                            confirmed: { type: boolean }
                        declare-task:
                          description: File through the upstream, as a task.
                          semantics:
                            interaction:
                              mode: task
                          steps:
                            file:
                              type: call
                              call: customs.file-slowly
                          mappings:
                            - { target: declarationId, value: $.file.declarationId }
                          outputParameters:
                            declarationId: { type: string }
                        file-ask-task:
                          description: File then pay through the upstream, as a task.
                          semantics:
                            interaction:
                              mode: task
                          steps:
                            fa:
                              type: call
                              call: customs.file-then-ask
                          mappings:
                            - { target: accepted, value: $.fa.accepted }
                          outputParameters:
                            accepted: { type: boolean }
                  exposes:
                    - type: mcp
                      transport: http
                      address: localhost
                      port: 0
                      namespace: ops-mcp
                      tools:
                        confirm-up: { description: Confirm, ref: ops.confirm-up }
                        declare-task: { description: Declare, ref: ops.declare-task }
                        file-ask-task: { description: File and pay, ref: ops.file-ask-task }
                    - type: control
                      address: localhost
                      port: 0
                %s
                """.formatted(IKANOS, upstreamPort, mayElicit, mayElicit, durability);
    }

    private static final String NONE = "";
    private static final String MEMORY = "      durability:\n        mode: memory\n";

    private String local() {
        String path = directory.resolve("consumer").toString().replace('\\', '/');
        return "      durability:\n        mode: local\n        store:\n          path: \"" + path
                + "\"\n";
    }

    private McpServerAdapter start(String durability, boolean declared) throws Exception {
        consumer = new Capability(YAML.readValue(consumerYaml(durability, declared),
                IkanosSpec.class));
        consumer.start();
        return mcp(consumer);
    }

    private static McpServerAdapter mcp(Capability capability) {
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode send(McpServerAdapter adapter, String method, ObjectNode params)
            throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("elicitation");
        capabilities.putObject("extensions").putObject(TASKS);
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private ObjectNode params(String text) throws Exception {
        return (ObjectNode) json.readTree(text);
    }

    private JsonNode awaitSettled(McpServerAdapter adapter, String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = send(adapter, "tasks/get", params("{\"taskId\":\"" + taskId + "\"}"))
                    .path("result");
            if (!"working".equals(task.path("status").asString())) {
                return task;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Task " + taskId + " did not settle");
    }

    private void answersAnInlineQuestion(String durability) throws Exception {
        McpServerAdapter adapter = start(durability, true);
        String call = "{\"name\":\"confirm-up\",\"arguments\":{}}";

        JsonNode asked = send(adapter, "tools/call", params(call)).path("result");
        assertEquals("input_required", asked.path("resultType").asString(), asked.toString());
        String key = "steps/ask/steps/confirm";
        assertEquals("Confirm the export?", asked.path("inputRequests").path(key)
                .path("params").path("message").asString(), asked.toString());

        ObjectNode retry = params(call);
        retry.put("requestState", asked.path("requestState").asString());
        retry.putObject("inputResponses").set(key,
                json.readTree("{\"action\":\"accept\",\"content\":{\"confirmed\":true}}"));
        JsonNode done = send(adapter, "tools/call", retry).path("result");

        assertEquals("complete", done.path("resultType").asString(), done.toString());
        assertTrue(done.toString().contains("\"confirmed\":true"), done.toString());
    }

    @Test
    void anUpstreamQuestionShouldReachTheCallerOfAnInlineCallInADurableMode() throws Exception {
        answersAnInlineQuestion(MEMORY);
    }

    @Test
    void anUpstreamQuestionShouldReachTheCallerOfAnInlineCallUnderNone() throws Exception {
        answersAnInlineQuestion(NONE);
    }

    @Test
    void aTaskShouldWaitOnAnUpstreamTask() throws Exception {
        McpServerAdapter adapter = start(MEMORY, true);

        String taskId = send(adapter, "tools/call",
                params("{\"name\":\"declare-task\",\"arguments\":{}}"))
                .path("result").path("taskId").asString();
        JsonNode done = awaitSettled(adapter, taskId);

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.toString().contains("DEC-1"), done.toString());
        assertEquals(1, filings.get(), "The upstream task filed the declaration once");
    }

    private String askThroughAnUpstreamTask(McpServerAdapter adapter) throws Exception {
        String taskId = send(adapter, "tools/call",
                params("{\"name\":\"file-ask-task\",\"arguments\":{}}"))
                .path("result").path("taskId").asString();
        JsonNode waiting = awaitSettled(adapter, taskId);
        assertEquals("input_required", waiting.path("status").asString(), waiting.toString());
        assertEquals("Pay the inspection fee?", waiting.path("inputRequests")
                .path("steps/fa/steps/confirm").path("params").path("message").asString(),
                waiting.toString());
        return taskId;
    }

    private void answerThroughAnUpstreamTask(McpServerAdapter adapter, String taskId)
            throws Exception {
        ObjectNode update = params("{\"taskId\":\"" + taskId + "\"}");
        update.putObject("inputResponses").set("steps/fa/steps/confirm",
                json.readTree("{\"action\":\"accept\",\"content\":{\"accepted\":true}}"));
        send(adapter, "tasks/update", update);
        JsonNode done = awaitSettled(adapter, taskId);

        assertEquals("completed", done.path("status").asString(), done.toString());
        assertTrue(done.toString().contains("\"accepted\":true"), done.toString());
        assertEquals(1, filings.get(), "The declaration filed before the question is filed once");
    }

    @Test
    void anUpstreamTaskThatAsksShouldBeAnsweredThroughTheConsumerTask() throws Exception {
        McpServerAdapter adapter = start(MEMORY, true);

        answerThroughAnUpstreamTask(adapter, askThroughAnUpstreamTask(adapter));
    }

    @Test
    void aQuestionFromAnUpstreamTaskShouldBeAnsweredAfterARestart() throws Exception {
        String taskId = askThroughAnUpstreamTask(start(local(), true));
        consumer.stop();
        consumer = null;

        answerThroughAnUpstreamTask(start(local(), true), taskId);
    }

    @Test
    void cancellingATaskWaitingOnAnUpstreamQuestionShouldCancelTheUpstreamTask()
            throws Exception {
        McpServerAdapter adapter = start(MEMORY, true);
        String taskId = askThroughAnUpstreamTask(adapter);
        JournaledRunner upstreamRunner = upstream.getDurability().runner();
        assertTrue(upstreamRunner.list(ExecutionStatus.CANCELLED, 10).isEmpty());

        send(adapter, "tasks/cancel", params("{\"taskId\":\"" + taskId + "\"}"));

        assertEquals("cancelled", send(adapter, "tasks/get",
                params("{\"taskId\":\"" + taskId + "\"}")).path("result").path("status")
                .asString());
        assertEquals(1, upstreamRunner.list(ExecutionStatus.CANCELLED, 10).size(),
                "The upstream task the consumer task waited on is cancelled too");
    }

    @Test
    void anUpstreamThatMayNotAskShouldFailTheCall() throws Exception {
        McpServerAdapter adapter = start(MEMORY, false);

        JsonNode result = send(adapter, "tools/call",
                params("{\"name\":\"confirm-up\",\"arguments\":{}}")).path("result");

        assertTrue(result.path("isError").asBoolean(), result.toString());
    }
}
