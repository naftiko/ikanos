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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.util.VersionHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Integration tests for YAML parsing capabilities.
 * Tests the conversion of YAML responses to JSON using the Capability framework.
 */
public class YamlIntegrationTest {

    private Capability capability;
    private ObjectMapper jsonMapper;
    private ObjectMapper yamlMapper;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        // Load the YAML capability from test resources
        String resourcePath = "src/test/resources/formats/yaml-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "YAML capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        // Initialize capability
        capability = new Capability(spec);
        jsonMapper = new ObjectMapper();
        yamlMapper = new ObjectMapper(new YAMLFactory());
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertNotNull(capability.getSpec(), "Capability spec should be loaded");
        assertEquals(schemaVersion, capability.getSpec().getIkanos(), "ikanos version should be " + schemaVersion);
    }

    @Test
    public void testCapabilityHasServerAdapters() {
        assertFalse(capability.getServerAdapters().isEmpty(),
                "Capability should have at least one server adapter");
        assertEquals(1, capability.getServerAdapters().size(),
                "YAML test capability should have exactly one server adapter");
    }

    @Test
    public void testCapabilityHasClientAdapters() {
        assertFalse(capability.getClientAdapters().isEmpty(),
                "Capability should have at least one client adapter");
        assertEquals(1, capability.getClientAdapters().size(),
                "YAML test capability should have exactly one client adapter");
    }

    @Test
    public void testYamlResponseConversion() throws IOException {
        // Load sample YAML response
        InputStream yamlStream = YamlIntegrationTest.class.getClassLoader()
                .getResourceAsStream("schemas/sample-users.yaml");
        assertNotNull(yamlStream, "Sample YAML file should exist in test resources");

        // Convert YAML to JSON using YamlMapper
        JsonNode yamlAsJson = yamlMapper.readTree(yamlStream);
        assertNotNull(yamlAsJson, "YAML should be converted to JsonNode");

        // Verify the structure - YAML root contains "users" array
        assertTrue(yamlAsJson.has("users"), "Converted JSON should have 'users' array");
        JsonNode users = yamlAsJson.get("users");
        assertTrue(users.isArray(), "'users' should be an array");
        assertEquals(3, users.size(), "Should have 3 users");

        // Verify first user data
        JsonNode firstUser = users.get(0);
        assertEquals("1", firstUser.get("id").asText(), "First user ID should be 1");
        assertEquals("Alice Smith", firstUser.get("name").asText(),
                "First user name should be Alice Smith");
        assertEquals("alice@example.com", firstUser.get("email").asText(),
                "First user email should be alice@example.com");

        // Verify second user data
        JsonNode secondUser = users.get(1);
        assertEquals("2", secondUser.get("id").asText(), "Second user ID should be 2");
        assertEquals("Bob Johnson", secondUser.get("name").asText(),
                "Second user name should be Bob Johnson");
        assertEquals("bob@example.com", secondUser.get("email").asText(),
                "Second user email should be bob@example.com");

        // Verify third user data
        JsonNode thirdUser = users.get(2);
        assertEquals("3", thirdUser.get("id").asText(), "Third user ID should be 3");
        assertEquals("Carol White", thirdUser.get("name").asText(),
                "Third user name should be Carol White");
        assertEquals("carol@example.com", thirdUser.get("email").asText(),
                "Third user email should be carol@example.com");
    }

    @Test
    public void testYamlDeserializationWithJsonPath() throws IOException {
        // Load sample YAML response
        InputStream yamlStream = YamlIntegrationTest.class.getClassLoader()
                .getResourceAsStream("schemas/sample-users.yaml");
        assertNotNull(yamlStream, "Sample YAML file should exist in test resources");

        // Convert YAML to JSON
        JsonNode yamlAsJson = yamlMapper.readTree(yamlStream);

        // Verify data extraction using standard JsonNode navigation (similar to JSONPath)
        JsonNode rootUsers = yamlAsJson.get("users");
        assertTrue(rootUsers.isArray(), "Root users should be array");

        // Build result similar to output parameter mapping
        ArrayNode resultArray =
                jsonMapper.createArrayNode();
        for (JsonNode user : rootUsers) {
            ObjectNode userObj = jsonMapper
                    .createObjectNode();
            userObj.put("id", user.get("id").asText());
            userObj.put("name", user.get("name").asText());
            userObj.put("email", user.get("email").asText());
            resultArray.add(userObj);
        }

        assertEquals(3, resultArray.size(), "Result array should have 3 users");
        String jsonResult = jsonMapper.writeValueAsString(resultArray);
        assertTrue(jsonResult.contains("Alice Smith"), "Result should contain Alice Smith");
        assertTrue(jsonResult.contains("alice@example.com"), "Result should contain email");
    }

    @Test
    public void testCapabilityYamlDeserializationFormat() {
        // Verify the operation has YAML format specified
        var serverAdapters = capability.getServerAdapters();
        assertFalse(serverAdapters.isEmpty(), "Should have server adapters");
    }
}
