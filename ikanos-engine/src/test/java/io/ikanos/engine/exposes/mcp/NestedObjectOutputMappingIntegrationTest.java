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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;

/**
 * Non-regression test for nested object output mapping.
 *
 * <p>Verifies end-to-end that a tool spec declaring a nested object property without a top-level
 * mapping (e.g. "specs") correctly resolves its children against the raw API response,
 * instead of producing null.</p>
 *
 * <p>Related fix: {@code Resolver#resolveOutputMappings} now recurses into sub-object
 * properties when no mapping key is present at the object level.</p>
 */
public class NestedObjectOutputMappingIntegrationTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private McpServerToolSpec toolSpec;
    private OperationStepExecutor stepExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/nested-object-output-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Test fixture not found: " + resourcePath);

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = yamlMapper.readValue(file, IkanosSpec.class);

        Capability capability = new Capability(spec);
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        McpServerSpec mcpSpec = adapter.getMcpServerSpec();

        toolSpec = mcpSpec.getTools().get("get-ship");
        stepExecutor = new OperationStepExecutor(null);
    }

    @Test
    public void outputParametersShouldDeserializeNestedSpecsProperty() {
        assertNotNull(toolSpec.getOutputParameters(), "outputParameters must not be null");
        assertFalse(toolSpec.getOutputParameters().isEmpty(), "outputParameters must not be empty");

        var rootSpec = toolSpec.getOutputParameters().get(0);
        assertEquals("object", rootSpec.getType());

        var specsProperty = rootSpec.getProperties().stream()
                .filter(p -> "specs".equals(p.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(specsProperty, "specs property must be deserialized");
        assertEquals("object", specsProperty.getType());
        assertFalse(specsProperty.getProperties().isEmpty(), "specs must have child properties");
    }

    @Test
    public void applyOutputMappingsShouldProduceNestedSpecsObject() throws Exception {
        String apiResponse = """
                {
                  "imo_number": "IMO-9321483",
                  "vessel_name": "Northern Star",
                  "operational_status": "active",
                  "year_built": 2015,
                  "gross_tonnage": 42000,
                  "dimensions": {
                    "length_overall": 229
                  }
                }
                """;

        String result = stepExecutor.applyOutputMappings(apiResponse, toolSpec.getOutputParameters());

        assertNotNull(result, "mapped result must not be null");

        JsonNode node = JSON_MAPPER.readTree(result);
        assertEquals("IMO-9321483", node.path("imo").asText());
        assertEquals("Northern Star", node.path("name").asText());
        assertEquals("active", node.path("status").asText());

        JsonNode specs = node.path("specs");
        assertFalse(specs.isNull(), "specs must not be null");
        assertFalse(specs.isMissingNode(), "specs must be present");
        assertTrue(specs.isObject(), "specs must be an object");
        assertEquals(2015, specs.path("yearBuilt").asInt());
        assertEquals(42000, specs.path("tonnage").asInt());
        assertEquals(229, specs.path("length").asInt());
    }
}
