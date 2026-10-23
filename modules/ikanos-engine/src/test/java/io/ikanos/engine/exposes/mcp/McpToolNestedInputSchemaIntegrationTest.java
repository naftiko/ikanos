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

import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads a capability whose MCP tools declare nested input schemas (#852) and asserts the
 * {@code inputSchema} advertised in {@code tools/list}, for an inline tool and for a tool that
 * reuses an aggregate flow.
 */
class McpToolNestedInputSchemaIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private static final String FEES_SCHEMA = """
            {"type":"array","description":"Fees to bill.",
             "items":{"type":"object",
               "properties":{
                 "add_on_code":{"type":"string","description":"Add-on code."},
                 "units":{"type":"number","minimum":0},
                 "unit_amount_cents":{"type":"integer"}},
               "required":["add_on_code","units"]}}
            """;

    @Test
    void toolsListShouldAdvertiseNestedInputSchemas() throws Exception {
        McpServerAdapter adapter = load();

        JsonNode tools = dispatch(adapter, "tools/list").path("result").path("tools");

        JsonNode inline = toolNamed(tools, "create-invoice").path("inputSchema");
        assertEquals(jsonMapper.readTree(FEES_SCHEMA), inline.path("properties").path("fees"));
        assertEquals(jsonMapper.readTree("[\"external_customer_id\",\"fees\"]"),
                inline.path("required"));
        assertEquals(jsonMapper.readTree("""
                {"type":"string","description":"Currency.","enum":["EUR","USD"]}
                """), inline.path("properties").path("currency"));

        JsonNode fromFlow = toolNamed(tools, "create-invoice-from-flow").path("inputSchema");
        assertEquals(jsonMapper.readTree(FEES_SCHEMA), fromFlow.path("properties").path("fees"));
    }

    private McpServerAdapter load() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Nested inputs"
                  description: "Nested MCP tool input schemas"
                capability:
                  aggregates:
                    - display: "Billing"
                      namespace: "billing-domain"
                      flows:
                        create-invoice:
                          description: "Create an invoice."
                          call: "billing.create-invoice"
                          inputParameters:
                            fees:
                              type: "array"
                              description: "Fees to bill."
                              items:
                                type: "object"
                                properties:
                                  add_on_code: { type: "string", description: "Add-on code." }
                                  units: { type: "number", minimum: 0 }
                                  unit_amount_cents: { type: "integer" }
                                required: ["add_on_code", "units"]
                  consumes:
                    - type: "http"
                      namespace: "billing"
                      baseUri: "http://localhost:1"
                      resources:
                        invoices:
                          path: "/invoices"
                          operations:
                            create-invoice:
                              method: "POST"
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "billing-mcp"
                      tools:
                        create-invoice:
                          description: "Create an invoice."
                          call: "billing.create-invoice"
                          inputParameters:
                            external_customer_id:
                              type: "string"
                              description: "External ID of the customer."
                            currency:
                              type: "string"
                              description: "Currency."
                              required: false
                              enum: ["EUR", "USD"]
                            fees:
                              type: "array"
                              description: "Fees to bill."
                              items:
                                type: "object"
                                properties:
                                  add_on_code: { type: "string", description: "Add-on code." }
                                  units: { type: "number", minimum: 0 }
                                  unit_amount_cents: { type: "integer" }
                                required: ["add_on_code", "units"]
                        create-invoice-from-flow:
                          ref: "billing-domain.create-invoice"
                """.formatted(IKANOS);
        IkanosSpec spec = yamlMapper.readValue(yaml, IkanosSpec.class);
        return (McpServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    private ObjectNode dispatch(McpServerAdapter adapter, String method) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        ObjectNode params = request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private static JsonNode toolNamed(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asString())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name + " in " + tools);
    }
}
