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
package io.naftiko.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.spec.exposes.ApiServerStepSpec;
import io.naftiko.spec.exposes.ApiServerCallSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for enhanced description metadata serialization.
 * Validates that descriptions are properly preserved in YAML/JSON roundtrips.
 */
public class DescriptionMetadataRoundTripTest {

    private ObjectMapper yamlMapper;
    private ObjectMapper jsonMapper;

    @BeforeEach
    public void setUp() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    @Test
    public void testApiServerStepSpecDescriptionYamlRoundTrip() throws Exception {
        ApiServerCallSpec call = new ApiServerCallSpec("users.fetch", null, "Retrieves user data");
        ApiServerStepSpec step = new ApiServerStepSpec(call, null, "Fetch user information");
        
        // Serialize to YAML string
        String yaml = yamlMapper.writeValueAsString(step);
        
        // Deserialize back
        ApiServerStepSpec restored = yamlMapper.readValue(yaml, ApiServerStepSpec.class);
        
        assertEquals("Fetch user information", restored.getDescription());
        assertNotNull(restored.getCall());
        assertEquals("users.fetch", restored.getCall().getOperation());
        assertEquals("Retrieves user data", restored.getCall().getDescription());
    }

    @Test
    public void testApiServerCallSpecDescriptionYamlRoundTrip() throws Exception {
        ApiServerCallSpec call = new ApiServerCallSpec("users.delete", null, "Remove a user permanently");
        
        // Serialize to YAML string
        String yaml = yamlMapper.writeValueAsString(call);
        
        // Deserialize back
        ApiServerCallSpec restored = yamlMapper.readValue(yaml, ApiServerCallSpec.class);
        
        assertEquals("users.delete", restored.getOperation());
        assertEquals("Remove a user permanently", restored.getDescription());
    }

    @Test
    public void testApiServerStepSpecDescriptionJsonRoundTrip() throws Exception {
        ApiServerCallSpec call = new ApiServerCallSpec("logs.write", null);
        ApiServerStepSpec step = new ApiServerStepSpec(call, null, "Write audit log");
        
        // Serialize to JSON string
        String json = jsonMapper.writeValueAsString(step);
        
        // Deserialize back
        ApiServerStepSpec restored = jsonMapper.readValue(json, ApiServerStepSpec.class);
        
        assertEquals("Write audit log", restored.getDescription());
        assertEquals("logs.write", restored.getCall().getOperation());
    }

    @Test
    public void testApiServerCallSpecWithoutDescriptionYamlRoundTrip() throws Exception {
        ApiServerCallSpec call = new ApiServerCallSpec("users.get");
        
        // Serialize to YAML string
        String yaml = yamlMapper.writeValueAsString(call);
        
        // Deserialize back
        ApiServerCallSpec restored = yamlMapper.readValue(yaml, ApiServerCallSpec.class);
        
        assertEquals("users.get", restored.getOperation());
        assertNull(restored.getDescription());
    }

    @Test
    public void testDescriptionExcludedWhenNullInSerialization() throws Exception {
        ApiServerStepSpec step = new ApiServerStepSpec(new ApiServerCallSpec("test"), null, null);
        
        // Serialize to YAML
        String yaml = yamlMapper.writeValueAsString(step);
        
        // Verify description field is not included (JsonInclude.NON_NULL)
        assertFalse(yaml.contains("description:"), 
            "Null description should not be serialized due to JsonInclude.NON_NULL");
    }

    @Test
    public void testDescriptionIncludedWhenPresentInSerialization() throws Exception {
        ApiServerStepSpec step = new ApiServerStepSpec(new ApiServerCallSpec("test"), null, "Test operation");
        
        // Serialize to YAML
        String yaml = yamlMapper.writeValueAsString(step);
        
        // Verify description field is included
        assertTrue(yaml.contains("description:"), 
            "Non-null description should be serialized");
        assertTrue(yaml.contains("Test operation"));
    }

    @Test
    public void testMultipleStepsWithDescriptionsYamlRoundTrip() throws Exception {
        String yamlContent = "call:\n  operation: users.get\n  description: Fetch user\n"
                + "description: Get user step\n";
        
        ApiServerStepSpec step = yamlMapper.readValue(yamlContent, ApiServerStepSpec.class);
        
        assertEquals("Get user step", step.getDescription());
        assertEquals("users.get", step.getCall().getOperation());
        assertEquals("Fetch user", step.getCall().getDescription());
    }

}
