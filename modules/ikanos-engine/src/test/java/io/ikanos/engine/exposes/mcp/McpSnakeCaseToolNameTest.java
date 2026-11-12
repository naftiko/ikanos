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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Issue #1006, as a component test: the engine, from capability load to MCP dispatch, against a
 * local stub upstream. An MCP tool keyed by a snake_case name is listed under that exact name, is
 * callable by it, and can be named in a skill's {@code tools} list.
 */
class McpSnakeCaseToolNameTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private static String capabilityYaml(RecordingUpstream upstream) {
        return """
                ikanos: "%s"
                capability:
                %s
                  exposes:
                    - type: "mcp"
                      port: 0
                      namespace: "shop-tools"
                      tools:
                        get_order_status:
                          description: "Status of order 42"
                          call: "shop.get-order"
                          outputParameters:
                            - type: "string"
                              mapping: "$.status"
                        list-orders:
                          description: "Kebab-case sibling, still valid"
                          call: "shop.get-order"
                      skills:
                        order-desk:
                          description: "Answer questions about an order."
                          tools: ["get_order_status", "list-orders"]
                """.formatted(IKANOS, upstream.consumesYaml().stripTrailing());
    }

    private static ProtocolDispatcher dispatcher(RecordingUpstream upstream) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(capabilityYaml(upstream),
                IkanosSpec.class);
        Capability capability = new Capability(spec);
        return new ProtocolDispatcher((McpServerAdapter) capability.getServerAdapters().get(0));
    }

    private static JsonNode dispatch(ProtocolDispatcher dispatcher, String method,
            ObjectNode params) {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        request.set("params", params);
        return dispatcher.dispatch(request).responseBody();
    }

    @Test
    void toolsListShouldAdvertiseSnakeCaseToolNameUnchanged() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            JsonNode response = dispatch(dispatcher(upstream), "tools/list",
                    JSON.createObjectNode());

            List<String> names = new ArrayList<>();
            response.path("result").path("tools").forEach(t -> names.add(t.path("name").asString()));
            assertThat(names).as("#1006: tools/list response %s", response)
                    .containsExactlyInAnyOrder("get_order_status", "list-orders");
        }
    }

    @Test
    void toolsCallShouldInvokeSnakeCaseTool() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            ObjectNode params = JSON.createObjectNode();
            params.put("name", "get_order_status");
            params.putObject("arguments");

            JsonNode result = dispatch(dispatcher(upstream), "tools/call", params).path("result");

            assertThat(result.path("isError").asBoolean()).as("#1006: tool call %s", result)
                    .isFalse();
            assertThat(result.toString()).contains("shipped");
            assertThat(upstream.requests()).hasSize(1);
        }
    }
}
