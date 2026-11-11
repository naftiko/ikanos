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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.util.YamlAliases;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads capabilities that share inputs and shapes through {@code definitions} (#910) and checks
 * that {@code tools/list} is byte for byte the one of the inline equivalent: same
 * {@code inputSchema}, same {@code outputSchema}, no reference sent to MCP clients.
 */
class McpDefinitionsIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final Path TUTORIAL_DIR =
            Paths.get("src", "test", "resources", "tutorial").toAbsolutePath().normalize();
    private static final Path DEFINITIONS_DIR =
            Paths.get("src", "test", "resources", "definitions").toAbsolutePath().normalize();

    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private static final String HEADER = """
            ikanos: "%s"
            info:
              display: "Fleet"
              description: "Shared definitions"
            capability:
              consumes:
                - type: http
                  namespace: registry
                  baseUri: "http://localhost:1"
                  resources:
                    ships:
                      path: "/ships"
                      operations:
                        list-ships:
                          method: GET
                        list-calls:
                          method: GET
            """.formatted(IKANOS);

    private static final String WITH_DEFINITIONS = HEADER + """
              aggregates:
                - display: "Fleet"
                  namespace: fleet
                  definitions:
                    inputParameterSets:
                      paging:
                        limit: { type: integer, required: false, description: "Maximum ships.", minimum: 1, maximum: 100 }
                    schemas:
                      ship:
                        type: object
                        properties:
                          imo: { type: string }
                          tonnage: { type: number }
                          crew:
                            type: array
                            items:
                              type: object
                              properties:
                                fullName: { type: string }
                  flows:
                    list-ships:
                      description: "List ships."
                      inputParameterSets: [paging]
                      inputParameters:
                        flag: { type: string, description: "Flag state.", required: false }
                      steps:
                        ships: { type: call, call: registry.list-ships }
                      mappings:
                        - target: ships
                          value: "$.ships.ships"
                        - target: flagship
                          value: "$.ships.flagship"
                      outputParameters:
                        ships:
                          type: array
                          items: { schema: ship }
                        flagship: { schema: ship }
              exposes:
                - type: mcp
                  address: localhost
                  port: 0
                  namespace: fleet-mcp
                  definitions:
                    inputParameterSets:
                      odata-list:
                        filter: { type: string, required: false, description: "OData V2 $filter expression." }
                        top: { type: integer, required: false, description: "Maximum rows to return (default 100)." }
                      tracing:
                        trace-id: { type: string, required: false, description: "Trace ID." }
                    schemas:
                      date-range:
                        type: object
                        description: "A date range."
                        properties:
                          from: { type: string, format: date }
                          to: { type: string, format: date }
                        required: [from]
                      period:
                        type: object
                        properties:
                          range: { schema: date-range, description: "Range of the period." }
                          ranges: { type: array, items: { schema: date-range } }
                  tools:
                    list-sales-orders:
                      description: "List sales orders."
                      inputParameterSets: [odata-list, tracing]
                      call: registry.list-ships
                    list-calls:
                      description: "List calls in a period."
                      inputParameterSets: [odata-list]
                      inputParameters:
                        top: { type: integer, required: true, description: "Rows to return." }
                        period: { schema: date-range, description: "Call date range.", required: true }
                        periods: { type: array, description: "Several periods.", items: { schema: period } }
                      call: registry.list-calls
                    list-fleet:
                      ref: fleet.list-ships
                    list-fleet-filtered:
                      ref: fleet.list-ships
                      inputParameterSets: [odata-list]
            """;

    private static final String INLINE = HEADER + """
              aggregates:
                - display: "Fleet"
                  namespace: fleet
                  flows:
                    list-ships:
                      description: "List ships."
                      inputParameters:
                        limit: { type: integer, required: false, description: "Maximum ships.", minimum: 1, maximum: 100 }
                        flag: { type: string, description: "Flag state.", required: false }
                      steps:
                        ships: { type: call, call: registry.list-ships }
                      mappings:
                        - target: ships
                          value: "$.ships.ships"
                        - target: flagship
                          value: "$.ships.flagship"
                      outputParameters:
                        ships:
                          type: array
                          items:
                            imo: { type: string }
                            tonnage: { type: number }
                            crew:
                              type: array
                              items:
                                fullName: { type: string }
                        flagship:
                          type: object
                          properties:
                            imo: { type: string }
                            tonnage: { type: number }
                            crew:
                              type: array
                              items:
                                fullName: { type: string }
              exposes:
                - type: mcp
                  address: localhost
                  port: 0
                  namespace: fleet-mcp
                  tools:
                    list-sales-orders:
                      description: "List sales orders."
                      inputParameters:
                        filter: { type: string, required: false, description: "OData V2 $filter expression." }
                        top: { type: integer, required: false, description: "Maximum rows to return (default 100)." }
                        trace-id: { type: string, required: false, description: "Trace ID." }
                      call: registry.list-ships
                    list-calls:
                      description: "List calls in a period."
                      inputParameters:
                        filter: { type: string, required: false, description: "OData V2 $filter expression." }
                        top: { type: integer, required: true, description: "Rows to return." }
                        period:
                          type: object
                          description: "Call date range."
                          required: true
                          properties:
                            from: { type: string, format: date }
                            to: { type: string, format: date }
                          requiredProperties: [from]
                        periods:
                          type: array
                          description: "Several periods."
                          items:
                            type: object
                            properties:
                              range:
                                type: object
                                description: "Range of the period."
                                properties:
                                  from: { type: string, format: date }
                                  to: { type: string, format: date }
                                required: [from]
                              ranges:
                                type: array
                                items:
                                  type: object
                                  description: "A date range."
                                  properties:
                                    from: { type: string, format: date }
                                    to: { type: string, format: date }
                                  required: [from]
                      call: registry.list-calls
                    list-fleet:
                      ref: fleet.list-ships
                    list-fleet-filtered:
                      ref: fleet.list-ships
                      inputParameters:
                        filter: { type: string, required: false, description: "OData V2 $filter expression." }
                        top: { type: integer, required: false, description: "Maximum rows to return (default 100)." }
            """;

    @Test
    void toolsListShouldBeByteForByteTheInlineEquivalent() throws Exception {
        String withDefinitions = toolsList(load(WITH_DEFINITIONS, null));
        String inline = toolsList(load(INLINE, null));

        assertEquals(inline, withDefinitions);
    }

    @Test
    void toolsListShouldSendNoReferenceToMcpClients() throws Exception {
        String toolsList = toolsList(load(WITH_DEFINITIONS, null));

        assertFalse(toolsList.contains("\"schema\""), toolsList);
        assertFalse(toolsList.contains("inputParameterSets"), toolsList);
        assertFalse(toolsList.contains("$ref"), toolsList);
    }

    @Test
    void toolsListShouldAdvertiseRefToolInputsFromItsSetsInsteadOfTheFlows() throws Exception {
        JsonNode tools = jsonMapper.readTree(toolsList(load(WITH_DEFINITIONS, null)))
                .path("result").path("tools");

        JsonNode filtered = toolNamed(tools, "list-fleet-filtered").path("inputSchema");
        JsonNode plain = toolNamed(tools, "list-fleet").path("inputSchema");

        assertTrue(filtered.path("properties").has("filter"), filtered.toString());
        assertFalse(filtered.path("properties").has("limit"), filtered.toString());
        assertTrue(plain.path("properties").has("limit"), plain.toString());
    }

    @Test
    void tutorialStep11ToolsListShouldBeByteForByteItsInlineVersion() throws Exception {
        String migrated = toolsList(loadTutorial("step-11-shipyard-fleet-manifest.yml"));
        String inline = toolsList(loadTutorial("step-11-shipyard-fleet-manifest-inline.yml"));

        assertEquals(inline, migrated);
    }

    @Test
    void importedAggregateShouldCarryItsDefinitions() throws Exception {
        Path file = DEFINITIONS_DIR.resolve("definitions-imports.yml");
        String yaml = Files.readString(file);

        String imported = toolsList(load(yaml, DEFINITIONS_DIR.toString()));
        // The imported flow declares its inputs required: an import deep-copies the aggregate
        // through YAML, which today turns `required: false` into `required: true` whether or
        // not definitions are used (a separate bug of the import round trip).
        String inline = toolsList(load(INLINE
                .replace("limit: { type: integer, required: false,", "limit: { type: integer,")
                .replace("flag: { type: string, description: \"Flag state.\", required: false }",
                        "flag: { type: string, description: \"Flag state.\" }"), null));

        assertEquals(inline, imported);
    }

    @Test
    void capabilityShouldFailToLoadWhenADefinitionIsUnknown() {
        String yaml = WITH_DEFINITIONS.replace("inputParameterSets: [odata-list, tracing]",
                "inputParameterSets: [odata-list, tracking]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(yaml, null));

        assertTrue(error.getMessage().contains("MCP adapter 'fleet-mcp', tool"
                + " 'list-sales-orders': unknown input parameter set 'tracking'"),
                error.getMessage());
    }

    private McpServerAdapter load(String yaml, String capabilityDir) throws Exception {
        IkanosSpec spec = IkanosMappers.yaml().readValue(
                YamlAliases.reject(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                IkanosSpec.class);
        Capability capability = new Capability(spec, capabilityDir);
        return (McpServerAdapter) capability.getServerAdapters().stream()
                .filter(McpServerAdapter.class::isInstance).findFirst().orElseThrow();
    }

    /**
     * Loads a tutorial capability with its shared files (bindings, imports, skills) resolved
     * from the tutorial test directory, as the tutorial integration tests do.
     */
    private McpServerAdapter loadTutorial(String name) throws Exception {
        String shared = TUTORIAL_DIR.resolve("shared").toUri().toString();
        String yaml = Files.readString(TUTORIAL_DIR.resolve(name))
                .replace("file:///./shared/", shared);
        return load(yaml, TUTORIAL_DIR.toString());
    }

    /** The {@code tools/list} response, serialized as the MCP transports send it. */
    private String toolsList(McpServerAdapter adapter) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/list");
        ObjectNode params = request.putObject("params");
        params.putObject("_meta").put("io.modelcontextprotocol/protocolVersion",
                ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        return jsonMapper.writeValueAsString(
                new ProtocolDispatcher(adapter).dispatch(request).responseBody());
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
