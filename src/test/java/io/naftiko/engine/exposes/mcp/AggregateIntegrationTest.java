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
package io.naftiko.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.aggregates.AggregateFunction;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import java.io.File;

/**
 * Integration tests for aggregate function ref resolution and semantics-to-hints derivation
 * through the full capability loading pipeline.
 */
public class AggregateIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Capability loadCapability(String path) throws Exception {
        File file = new File(path);
        assertTrue(file.exists(), "Test file should exist: " + path);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
        return new Capability(spec);
    }

    // ── Basic ref resolution ──

    @Test
    void refShouldResolveCallFromAggregateFunction() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        McpServerToolSpec tool = serverSpec.getTools().get(0);
        assertEquals("get-forecast", tool.getName());
        // call is no longer copied to the tool spec — it is resolved at runtime
        assertNull(tool.getCall(), "call should not be on tool spec — delegated to aggregate");

        // Verify call is available via the runtime aggregate function
        AggregateFunction fn = capability.lookupFunction(tool.getRef());
        assertNotNull(fn.getCall(), "call should be available on the aggregate function");
        assertEquals("weather-api.get-forecast", fn.getCall().getOperation());
    }

    @Test
    void refShouldResolveInputParametersFromFunction() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        McpServerToolSpec tool = serverSpec.getTools().get(0);
        // inputParameters are no longer copied to the tool spec
        assertTrue(tool.getInputParameters().isEmpty(),
                "inputParameters should not be on tool spec — delegated to aggregate");

        // Verify inputParameters are available via the runtime aggregate function
        AggregateFunction fn = capability.lookupFunction(tool.getRef());
        assertEquals(1, fn.getInputParameters().size());
        assertEquals("location", fn.getInputParameters().get(0).getName());
    }

    @Test
    void refShouldInheritDescriptionWhenOmitted() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        // Second tool omits description — should inherit from function
        McpServerToolSpec tool = serverSpec.getTools().get(1);
        assertEquals("get-forecast-inherited", tool.getName());
        assertEquals("Fetch current weather forecast for a location.", tool.getDescription());
    }

    @Test
    void restOperationRefShouldInheritNameAndDescription() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        // REST adapter is the second server adapter
        io.naftiko.engine.exposes.rest.RestServerAdapter restAdapter =
                (io.naftiko.engine.exposes.rest.RestServerAdapter) capability.getServerAdapters()
                        .get(1);
        io.naftiko.spec.exposes.RestServerSpec restSpec =
                (io.naftiko.spec.exposes.RestServerSpec) restAdapter.getSpec();

        // Second operation (POST) omits name/description — inherited from function
        io.naftiko.spec.exposes.RestServerOperationSpec op =
                restSpec.getResources().get(0).getOperations().get(1);
        assertEquals("POST", op.getMethod());
        assertEquals("get-forecast", op.getName());
        assertEquals("Fetch current weather forecast for a location.", op.getDescription());
        // call is no longer copied — resolved at runtime
        assertNull(op.getCall(), "call should not be on operation spec — delegated to aggregate");
    }

    // ── Semantics → hints derivation ──

    @Test
    void safeFunctionShouldDeriveReadOnlyHint() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        McpServerToolSpec tool = serverSpec.getTools().get(0);
        assertNotNull(tool.getHints(), "Hints should be derived from semantics");
        assertEquals(true, tool.getHints().getReadOnly());
        assertEquals(false, tool.getHints().getDestructive());
        assertEquals(true, tool.getHints().getIdempotent());
    }

    // ── Hints override ──

    @Test
    void toolWithNoExplicitHintsShouldGetFullDerivation() throws Exception {
        Capability capability =
                loadCapability("src/test/resources/aggregates/aggregate-hints-override.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        // read-items: safe=true, idempotent=true → readOnly=true, destructive=false, idempotent=true
        McpServerToolSpec readTool = serverSpec.getTools().get(0);
        assertEquals("read-items", readTool.getName());
        assertEquals(true, readTool.getHints().getReadOnly());
        assertEquals(false, readTool.getHints().getDestructive());
        assertEquals(true, readTool.getHints().getIdempotent());
        assertNull(readTool.getHints().getOpenWorld());
    }

    @Test
    void toolWithPartialHintOverrideShouldMerge() throws Exception {
        Capability capability =
                loadCapability("src/test/resources/aggregates/aggregate-hints-override.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        // read-items-open: same derivation + openWorld=true from tool
        McpServerToolSpec openTool = serverSpec.getTools().get(1);
        assertEquals("read-items-open", openTool.getName());
        assertEquals(true, openTool.getHints().getReadOnly());
        assertEquals(false, openTool.getHints().getDestructive());
        assertEquals(true, openTool.getHints().getIdempotent());
        assertEquals(true, openTool.getHints().getOpenWorld());
    }

    @Test
    void toolWithExplicitHintsShouldOverrideDerived() throws Exception {
        Capability capability =
                loadCapability("src/test/resources/aggregates/aggregate-hints-override.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        // delete-item: safe=false → readOnly=false; explicit override: destructive=true, openWorld=false
        McpServerToolSpec deleteTool = serverSpec.getTools().get(2);
        assertEquals("delete-item", deleteTool.getName());
        assertEquals(false, deleteTool.getHints().getReadOnly());
        assertEquals(true, deleteTool.getHints().getDestructive());
        assertEquals(true, deleteTool.getHints().getIdempotent()); // from semantics
        assertEquals(false, deleteTool.getHints().getOpenWorld()); // from explicit
    }

    @Test
    void toolWithoutRefShouldNotHaveHints() throws Exception {
        Capability capability =
                loadCapability("src/test/resources/aggregates/aggregate-hints-override.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec serverSpec = adapter.getMcpServerSpec();

        McpServerToolSpec plainTool = serverSpec.getTools().get(3);
        assertEquals("plain-tool", plainTool.getName());
        assertNull(plainTool.getHints());
    }

    // ── Wire format ──

    @Test
    void toolsListShouldIncludeDerivedAnnotationsInWireFormat() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-basic.yaml");
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);

        JsonNode response = dispatcher.dispatch(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        JsonNode tools = response.path("result").path("tools");
        assertEquals(2, tools.size());

        JsonNode forecastTool = tools.get(0);
        assertEquals("get-forecast", forecastTool.path("name").asText());

        JsonNode annotations = forecastTool.path("annotations");
        assertFalse(annotations.isMissingNode(), "Should have annotations from derived hints");
        assertEquals(true, annotations.path("readOnlyHint").asBoolean());
        assertEquals(false, annotations.path("destructiveHint").asBoolean());
        assertEquals(true, annotations.path("idempotentHint").asBoolean());
        assertTrue(annotations.path("openWorldHint").isMissingNode(),
                "openWorld should not be set");
    }

    // ── Error cases ──

    @Test
    void unknownRefShouldFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> loadCapability("src/test/resources/aggregates/aggregate-invalid-ref.yaml"));
    }

}
