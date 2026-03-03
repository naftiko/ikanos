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
package io.naftiko.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Capability external reference resolution.
 * Validates that external refs are loaded and accessible from Capability instances.
 */
public class CapabilityExternalRefIntegrationTest {

    private ObjectMapper yamlMapper;

    @BeforeEach
    public void setUp() {
        yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Test
    public void testCapabilityWithFileExternalRef(@TempDir Path tempDir) throws Exception {
        // Create config file
        String configContent = """
                {
                  "API_TOKEN": "secret-token-123",
                  "API_URL": "https://api.example.com"
                }
                """;
        
        Path configFile = tempDir.resolve("api-config.json");
        Files.writeString(configFile, configContent);

        // Create capability YAML with external ref
        String capabilityYaml = """
                naftiko: "0.4"
                info:
                  label: Test Capability with External Refs
                  description: Tests external reference resolution
                externalRefs:
                  - name: api-config
                    description: API Configuration
                    type: json
                    resolution: file
                    uri: api-config.json
                    keys:
                      api_token: API_TOKEN
                      api_url: API_URL
                capability:
                  exposes:
                    - type: api
                      port: 8080
                      namespace: test-api
                  consumes: []
                """;

        Path capFile = tempDir.resolve("naftiko.yaml");
        Files.writeString(capFile, capabilityYaml);

        // Parse and create capability
        NaftikoSpec spec = yamlMapper.readValue(capFile.toFile(), NaftikoSpec.class);
        assertEquals(1, spec.getExternalRefs().size());

        Capability capability = new Capability(spec, tempDir.toString());

        // Verify external refs were resolved
        Map<String, Object> extRefVars = capability.getExternalRefVariables();
        assertNotNull(extRefVars);
        assertEquals(2, extRefVars.size());
        assertEquals("secret-token-123", extRefVars.get("api_token"));
        assertEquals("https://api.example.com", extRefVars.get("api_url"));
    }

    @Test
    public void testCapabilityWithRuntimeExternalRef() throws Exception {
        // Set system property (simulating environment variable)
        String propName = "TEST_RUNTIME_VAR_" + System.currentTimeMillis();
        String propValue = "runtime-secret-value";
        System.setProperty(propName, propValue);

        try {
            // Create capability YAML with runtime external ref
            String capabilityYaml = "naftiko: \"0.4\"\n"
                    + "info:\n"
                    + "  label: Test Capability with Runtime Refs\n"
                    + "  description: Tests runtime external reference resolution\n"
                    + "externalRefs:\n"
                    + "  - name: runtime-config\n"
                    + "    description: Runtime Configuration\n"
                    + "    type: json\n"
                    + "    resolution: runtime\n"
                    + "    keys:\n"
                    + "      secret: " + propName + "\n"
                    + "capability:\n"
                    + "  exposes:\n"
                    + "    - type: api\n"
                    + "      port: 8081\n"
                    + "      namespace: test-api-runtime\n"
                    + "  consumes: []\n";

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            NaftikoSpec spec = mapper.readValue(capabilityYaml, NaftikoSpec.class);
            assertEquals(1, spec.getExternalRefs().size());

            Capability capability = new Capability(spec, ".");

            // Verify external refs were resolved
            Map<String, Object> extRefVars = capability.getExternalRefVariables();
            assertNotNull(extRefVars);
            assertEquals(1, extRefVars.size());
            assertEquals(propValue, extRefVars.get("secret"));
        } finally {
            System.clearProperty(propName);
        }
    }

    @Test
    public void testCapabilityWithMultipleExternalRefs(@TempDir Path tempDir) throws Exception {
        // Create config file
        String configContent = """
                {
                  "TOKEN": "file-token-123",
                  "ENDPOINT": "https://api.example.com/v1"
                }
                """;
        
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, configContent);

        // Set system property for runtime ref
        String runtimeProp = "CUSTOM_SETTING_" + System.currentTimeMillis();
        String runtimeValue = "custom-setting-value";
        System.setProperty(runtimeProp, runtimeValue);

        try {
            // Create capability YAML
            String capabilityYaml = "naftiko: \"0.4\"\n"
                    + "info:\n"
                    + "  label: Test Capability Multi-Refs\n"
                    + "  description: Tests multiple external references\n"
                    + "externalRefs:\n"
                    + "  - name: file-config\n"
                    + "    description: File-based Configuration\n"
                    + "    type: json\n"
                    + "    resolution: file\n"
                    + "    uri: config.json\n"
                    + "    keys:\n"
                    + "      token: TOKEN\n"
                    + "      endpoint: ENDPOINT\n"
                    + "  - name: runtime-config\n"
                    + "    description: Runtime Configuration\n"
                    + "    type: json\n"
                    + "    resolution: runtime\n"
                    + "    keys:\n"
                    + "      custom_setting: " + runtimeProp + "\n"
                    + "capability:\n"
                    + "  exposes:\n"
                    + "    - type: api\n"
                    + "      port: 8082\n"
                    + "      namespace: multi-ref-api\n"
                    + "  consumes: []\n";

            Path capFile = tempDir.resolve("naftiko.yaml");
            Files.writeString(capFile, capabilityYaml);

            NaftikoSpec spec = yamlMapper.readValue(capFile.toFile(), NaftikoSpec.class);
            assertEquals(2, spec.getExternalRefs().size());

            Capability capability = new Capability(spec, tempDir.toString());

            // Verify all refs were resolved
            Map<String, Object> extRefVars = capability.getExternalRefVariables();
            assertNotNull(extRefVars);
            assertEquals(3, extRefVars.size());
            assertEquals("file-token-123", extRefVars.get("token"));
            assertEquals("https://api.example.com/v1", extRefVars.get("endpoint"));
            assertEquals(runtimeValue, extRefVars.get("custom_setting"));
        } finally {
            System.clearProperty(runtimeProp);
        }
    }

    @Test
    public void testCapabilityWithoutExternalRefs() throws Exception {
        // Create capability YAML without external refs
        String capabilityYaml = """
                naftiko: "0.4"
                info:
                  label: Test Capability No Refs
                  description: Tests capability without external references
                capability:
                  exposes:
                    - type: api
                      port: 8083
                      namespace: no-ref-api
                  consumes: []
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        NaftikoSpec spec = mapper.readValue(capabilityYaml, NaftikoSpec.class);
        
        // Should have empty or null external refs
        assertTrue(spec.getExternalRefs() == null || spec.getExternalRefs().isEmpty());

        Capability capability = new Capability(spec, ".");

        // Verify external ref variables are empty
        Map<String, Object> extRefVars = capability.getExternalRefVariables();
        assertNotNull(extRefVars);
        assertTrue(extRefVars.isEmpty());
    }

    @Test
    public void testCapabilityExternalRefFailsWithMissingFile(@TempDir Path tempDir) throws Exception {
        // Create capability YAML pointing to non-existent config file
        String capabilityYaml = """
                naftiko: "0.4"
                info:
                  label: Test Failed Capability
                  description: Tests failed initialization
                externalRefs:
                  - name: missing-config
                    description: Missing config file
                    type: json
                    resolution: file
                    uri: does-not-exist.json
                    keys:
                      token: TOKEN
                capability:
                  exposes:
                    - type: api
                      port: 8084
                      namespace: test-api-fail
                  consumes: []
                """;

        Path capFile = tempDir.resolve("naftiko.yaml");
        Files.writeString(capFile, capabilityYaml);

        NaftikoSpec spec = yamlMapper.readValue(capFile.toFile(), NaftikoSpec.class);

        // Should throw IOException during initialization
        assertThrows(IOException.class, () -> {
            new Capability(spec, tempDir.toString());
        });
    }

}
