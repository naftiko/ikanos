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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the bytes of {@code tools/list}. Clients cache the prompt prefix that holds tool
 * definitions, so any change in these bytes, between restarts or after a refactor, costs every
 * client a cache miss.
 *
 * <p>Each fixture's {@code tools} array is compared byte for byte with a golden file under
 * {@code src/test/resources/mcp/golden/}. When an intended change alters the output, regenerate
 * the files with {@code -Dikanos.golden.update=true} and review the diff.</p>
 */
class ToolsListHandlerGoldenTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String CATALOG = "src/test/resources/mcp/mcp-tool-catalog-capability.yaml";
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/mcp/golden");

    @ParameterizedTest
    @ValueSource(strings = {
        CATALOG,
        "src/test/resources/mcp/mcp-capability.yaml",
        "src/test/resources/mcp/mcp-hints-capability.yaml",
        "src/test/resources/aggregates/aggregate-hints-override.yaml"
    })
    void toolsListShouldMatchGoldenBytes(String fixture) throws Exception {
        String actual = toolsArrayJson(fixture);
        Path golden = GOLDEN_DIR.resolve(Path.of(fixture).getFileName().toString()
                .replaceAll("\\.ya?ml$", "") + ".tools.json");

        if (Boolean.getBoolean("ikanos.golden.update")) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, actual + "\n", StandardCharsets.UTF_8);
        }

        assertEquals(Files.readString(golden, StandardCharsets.UTF_8).stripTrailing(), actual,
                "tools/list bytes changed for " + fixture);
    }

    @Test
    @DisplayName("Two engines built from the same capability answer tools/list with the same bytes")
    void toolsListShouldBeByteIdenticalAcrossEngineStarts() throws Exception {
        assertEquals(JSON.writeValueAsString(toolsListResponse(CATALOG)),
                JSON.writeValueAsString(toolsListResponse(CATALOG)));
    }

    @Test
    void toolsListShouldKeepAuthoredToolOrder() throws Exception {
        McpServerSpec serverSpec = (McpServerSpec) YAML.readValue(new File(CATALOG),
                IkanosSpec.class).getCapability().getExposes().getFirst();
        List<String> listed = new ArrayList<>();
        toolsListResponse(CATALOG).path("result").path("tools")
                .forEach(tool -> listed.add(tool.path("name").asString()));

        assertEquals(List.copyOf(serverSpec.getTools().keySet()), listed);
    }

    static String toolsArrayJson(String fixture) throws Exception {
        return JSON.writeValueAsString(toolsListResponse(fixture).path("result").path("tools"));
    }

    static JsonNode toolsListResponse(String fixture) throws Exception {
        IkanosSpec spec = YAML.readValue(new File(fixture), IkanosSpec.class);
        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().stream()
                .filter(McpServerAdapter.class::isInstance).findFirst().orElseThrow();
        DispatchResult result = new ProtocolDispatcher(adapter).dispatch(JSON.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
                  "io.modelcontextprotocol/protocolVersion":"%s"}}}
                """.formatted(ProtocolDispatcher.MCP_PROTOCOL_VERSION)));
        return result.responseBody();
    }
}
