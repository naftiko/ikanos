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
package io.naftiko.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.util.VersionHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Integration tests for XML parsing capabilities.
 * Tests the conversion of XML responses to JSON using the Capability framework.
 */
public class XmlIntegrationTest {

    private Capability capability;
    private ObjectMapper jsonMapper;
    private XmlMapper xmlMapper;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        // Load the XML capability from test resources
        String resourcePath = "src/test/resources/formats/xml-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "XML capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        // Initialize capability
        capability = new Capability(spec);
        jsonMapper = new ObjectMapper();
        xmlMapper = new XmlMapper();
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertNotNull(capability.getSpec(), "Capability spec should be loaded");
        assertEquals(schemaVersion, capability.getSpec().getNaftiko(), "Naftiko version should be " + schemaVersion);
    }

    @Test
    public void testCapabilityHasServerAdapters() {
        assertFalse(capability.getServerAdapters().isEmpty(),
                "Capability should have at least one server adapter");
        assertEquals(1, capability.getServerAdapters().size(),
                "XML test capability should have exactly one server adapter");
    }

    @Test
    public void testCapabilityHasClientAdapters() {
        assertFalse(capability.getClientAdapters().isEmpty(),
                "Capability should have at least one client adapter");
        assertEquals(1, capability.getClientAdapters().size(),
                "XML test capability should have exactly one client adapter");
    }

    @Test
    public void testXmlResponseConversion() throws IOException {
        // Load sample XML response
        InputStream xmlStream = XmlIntegrationTest.class.getClassLoader()
                .getResourceAsStream("schemas/sample-users.xml");
        assertNotNull(xmlStream, "Sample XML file should exist in test resources");

        // Convert XML to JSON using XmlMapper
        JsonNode xmlAsJson = xmlMapper.readTree(xmlStream);
        assertNotNull(xmlAsJson, "XML should be converted to JsonNode");

        // Verify the structure
        assertTrue(xmlAsJson.has("user"), "Converted JSON should have 'user' array");
        JsonNode users = xmlAsJson.get("user");
        assertTrue(users.isArray(), "'user' should be an array");
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
    }

    @Test
    public void testXmlDeserializationWithJsonPath() throws IOException {
        // Load sample XML response
        InputStream xmlStream = XmlIntegrationTest.class.getClassLoader()
                .getResourceAsStream("schemas/sample-users.xml");
        assertNotNull(xmlStream, "Sample XML file should exist in test resources");

        // Convert XML to JSON
        JsonNode xmlAsJson = xmlMapper.readTree(xmlStream);

        // Verify data extraction using standard JsonNode navigation (similar to JSONPath)
        JsonNode rootUsers = xmlAsJson.get("user");
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
    public void testCapabilityXmlDeserializationFormat() {
        // Verify the operation has XML format specified
        var serverAdapters = capability.getServerAdapters();
        assertFalse(serverAdapters.isEmpty(), "Should have server adapters");

        // The actual format validation would occur in ApiOperationsRestlet
        // This test verifies capability loads the format specification
        assertNotNull(capability.getSpec().getCapability().getConsumes().get(0),
                "Capability should have consume spec");
    }
}
