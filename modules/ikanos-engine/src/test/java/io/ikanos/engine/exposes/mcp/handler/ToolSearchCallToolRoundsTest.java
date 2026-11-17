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
package io.ikanos.engine.exposes.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.search.ToolSearchCatalog;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call meta-tool runs a batch of plain calls. A tool that may ask for input, or that runs as
 * a task, needs the round trips of a direct {@code tools/call}, so the batch sends it there.
 */
class ToolSearchCallToolRoundsTest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    @TempDir
    Path directory;

    private Capability capability;
    private McpServerAdapter adapter;
    private McpServerSpec serverSpec;
    private ToolsCallHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        String path = directory.resolve("durable").toString().replace('\\', '/');
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Departure operations"
                  description: "Tools that ask, run as tasks, or neither"
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
                      archive-voyage:
                        description: Archive a finished voyage.
                        semantics:
                          interaction:
                            mode: task
                        inputParameters:
                          voyageId:
                            type: string
                            description: Voyage to archive
                        mappings:
                        - target: archived
                          value: "true"
                        outputParameters:
                          archived:
                            type: string
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: shipyard-ops
                    description: Shipyard operations.
                    tools:
                      confirm-departure:
                        description: Confirm a departure.
                        hints:
                          readOnly: false
                          destructive: false
                        ref: departure.confirm-departure
                      archive-voyage:
                        description: Archive a finished voyage.
                        hints:
                          readOnly: false
                          destructive: false
                        ref: departure.archive-voyage
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: local
                      store:
                        path: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), path);
        capability = new Capability(IkanosMappers.yaml().readValue(yaml, IkanosSpec.class));
        for (ServerAdapter serverAdapter : capability.getServerAdapters()) {
            if (serverAdapter instanceof McpServerAdapter mcp) {
                adapter = mcp;
            }
        }
        serverSpec = adapter.getMcpServerSpec();
        handler = new ToolsCallHandler(adapter, List.of(), List.of(), List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        capability.getDurability().close();
    }

    private ToolSearchCatalog catalog() {
        return new ToolSearchCatalog(serverSpec, adapter.getTools(), adapter.getToolLabels(),
                adapter.getToolTags(), Optional.empty());
    }

    private JsonNode callOnly(String name) {
        ObjectNode arguments = JSON.createObjectNode();
        ObjectNode call = arguments.putArray("calls").addObject();
        call.put("name", name);
        call.putObject("arguments").put("voyageId", "VOY-2026-043");
        return handler.callTool(catalog(), arguments)
                .path("structuredContent").path("results").get(0).path("result");
    }

    @Test
    void callToolShouldSendAToolThatMayAskToADirectCall() {
        assertTrue(catalog().isSearchable("confirm-departure"));

        JsonNode result = callOnly("confirm-departure");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertEquals("confirm-departure may ask for input: call it directly with tools/call,"
                + " not through call-tool.",
                result.path("content").get(0).path("text").asString());
    }

    @Test
    void callToolShouldSendATaskToolToADirectCall() {
        assertTrue(catalog().isSearchable("archive-voyage"));

        JsonNode result = callOnly("archive-voyage");

        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertEquals("archive-voyage runs as a task: call it directly with tools/call,"
                + " not through call-tool.",
                result.path("content").get(0).path("text").asString());
    }
}
