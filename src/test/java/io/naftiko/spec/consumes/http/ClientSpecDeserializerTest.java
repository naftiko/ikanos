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
package io.naftiko.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.consumes.ClientSpec;

/**
 * Unit tests for ClientSpec deserialization.
 * Tests discrimination between HttpClientSpec and ImportedConsumesHttpSpec.
 */
public class ClientSpecDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void testDeserializeImportedConsumes() throws Exception {
        String yaml = """
            type: "http"
            location: "./api.yml"
            import: "myapi"
            as: "myapi-v1"
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof ImportedConsumesHttpSpec);
        ImportedConsumesHttpSpec imported = (ImportedConsumesHttpSpec) result;
        assertEquals("./api.yml", imported.getLocation());
        assertEquals("myapi", imported.getImportNamespace());
        assertEquals("myapi-v1", imported.getAlias());
    }

    @Test
    public void testDeserializeHttpClientSpec() throws Exception {
        String yaml = """
            type: "http"
            namespace: "api"
            baseUri: "https://api.example.com"
            resources: []
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof HttpClientSpec);
        HttpClientSpec http = (HttpClientSpec) result;
        assertEquals("api", http.getNamespace());
        assertEquals("https://api.example.com", http.getBaseUri());
    }

    @Test
    public void testDeserializeImportWithoutAlias() throws Exception {
        String yaml = """
            type: "http"
            location: "./shared.yml"
            import: "shared-api"
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof ImportedConsumesHttpSpec);
        ImportedConsumesHttpSpec imported = (ImportedConsumesHttpSpec) result;
        assertNull(imported.getAlias());
    }

    @Test
    public void testDeserializeImportWithEmptyAlias() throws Exception {
        String yaml = """
            type: "http"
            location: "./shared.yml"
            import: "shared-api"
            as: ""
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof ImportedConsumesHttpSpec);
        ImportedConsumesHttpSpec imported = (ImportedConsumesHttpSpec) result;
        assertEquals("", imported.getAlias());
    }

    @Test
    public void testDeserializeImportedConsumesWithDescriptionShouldPopulateDescriptionField() throws Exception {
        String yaml = """
            type: "http"
            location: "./api.yml"
            import: "myapi"
            description: "Manages project tasks and issues."
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof ImportedConsumesHttpSpec);
        ImportedConsumesHttpSpec imported = (ImportedConsumesHttpSpec) result;
        assertEquals("Manages project tasks and issues.", imported.getDescription());
    }

    @Test
    public void testDeserializeImportedConsumesWithoutDescriptionShouldLeaveDescriptionNull() throws Exception {
        String yaml = """
            type: "http"
            location: "./api.yml"
            import: "myapi"
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertTrue(result instanceof ImportedConsumesHttpSpec);
        ImportedConsumesHttpSpec imported = (ImportedConsumesHttpSpec) result;
        assertNull(imported.getDescription());
    }
}
