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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.engine.consumes.ClientAdapter;

/**
 * Integration tests for consumes imports in full capability loading.
 * Tests end-to-end loading, validation, and operation calls with imported adapters.
 */
public class CapabilityImportIntegrationTest {

    private Path tempDir;
    private Capability capability;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("naftiko-cap-test-");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (capability != null) {
            capability.stop();
        }
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // Ignore
                }
            });
    }

    @Test
    public void testCapabilityLoadsWithImportedConsumes() throws Exception {
        // Create source consumes file
        String sourceConsumesYaml = """
            naftiko: "0.5"
            consumes:
              - type: "http"
                namespace: "json-api"
                baseUri: "https://api.example.com"
                resources: []
            """;

        Files.writeString(tempDir.resolve("json-api.consumes.yml"), sourceConsumesYaml);

        // Create capability with import
        String capabilityYaml = """
            naftiko: "0.5"
            info:
              label: "Test Capability"
              description: "Tests imported adapters"
            capability:
              consumes:
                - location: "./json-api.consumes.yml"
                  import: "json-api"
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 9999
                  namespace: "proxy"
                  resources: []
            """;

        Path capPath = tempDir.resolve("test-capability.yml");
        Files.writeString(capPath, capabilityYaml);

        // Load capability
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(capPath.toFile(), NaftikoSpec.class);

        capability = new Capability(spec, tempDir.toString());

        // Verify consumes are loaded
        assertEquals(1, capability.getClientAdapters().size());
          ClientAdapter adapter = capability.getClientAdapters().get(0);
        assertEquals("json-api", adapter.getSpec().getNamespace());
    }

    @Test
    public void testCapabilityWithMixedConsumes() throws Exception {
        // Create source consumes
        String sourceConsumesYaml = """
            naftiko: "0.5"
            consumes:
              - type: "http"
                namespace: "external-api"
                baseUri: "https://external.example.com"
                resources: []
            """;

        Files.writeString(tempDir.resolve("external.consumes.yml"), sourceConsumesYaml);

        // Create capability with both inline and imported consumes
        String capabilityYaml = """
            naftiko: "0.5"
            info:
              label: "Mixed Capability"
              description: "Tests both inline and imported consumes"
            capability:
              consumes:
                - type: "http"
                  namespace: "local-api"
                  baseUri: "https://local.example.com"
                  resources: []
                - location: "./external.consumes.yml"
                  import: "external-api"
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 9998
                  namespace: "proxy"
                  resources: []
            """;

        Path capPath = tempDir.resolve("mixed-capability.yml");
        Files.writeString(capPath, capabilityYaml);

        // Load capability
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(capPath.toFile(), NaftikoSpec.class);

        capability = new Capability(spec, tempDir.toString());

        // Verify both consumes are loaded
        assertEquals(2, capability.getClientAdapters().size());
        
        ClientAdapter adapter1 = capability.getClientAdapters().get(0);
        assertEquals("local-api", adapter1.getSpec().getNamespace());

        ClientAdapter adapter2 = capability.getClientAdapters().get(1);
        assertEquals("external-api", adapter2.getSpec().getNamespace());
    }

    @Test
    public void testCapabilityImportWithAlias() throws Exception {
        // Create source consumes
        String sourceConsumesYaml = """
            naftiko: "0.5"
            consumes:
              - type: "http"
                namespace: "api"
                baseUri: "https://api.example.com"
                resources: []
            """;

        Files.writeString(tempDir.resolve("api.consumes.yml"), sourceConsumesYaml);

        // Create capability with import using alias
        String capabilityYaml = """
            naftiko: "0.5"
            info:
              label: "Aliased Capability"
              description: "Tests import with alias"
            capability:
              consumes:
                - location: "./api.consumes.yml"
                  import: "api"
                  as: "my-api"
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 9997
                  namespace: "proxy"
                  resources: []
            """;

        Path capPath = tempDir.resolve("aliased-capability.yml");
        Files.writeString(capPath, capabilityYaml);

        // Load capability
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(capPath.toFile(), NaftikoSpec.class);

        capability = new Capability(spec, tempDir.toString());

        // Verify alias is applied
        assertEquals(1, capability.getClientAdapters().size());
        ClientAdapter adapter = capability.getClientAdapters().get(0);
        assertEquals("my-api", adapter.getSpec().getNamespace());
    }

    @Test
    public void testCapabilityImportFileNotFound() throws Exception {
        // Create capability with non-existent import
        String capabilityYaml = """
            naftiko: "0.5"
            info:
              label: "Bad Capability"
              description: "Bad import path"
            capability:
              consumes:
                - location: "./nonexistent.consumes.yml"
                  import: "api"
              exposes:
                - type: "rest"
                  address: "localhost"
                  port: 9996
                  namespace: "proxy"
                  resources: []
            """;

        Path capPath = tempDir.resolve("bad-capability.yml");
        Files.writeString(capPath, capabilityYaml);

        // Load capability
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(capPath.toFile(), NaftikoSpec.class);

        // Should throw IOException due to missing file
        assertThrows(IOException.class, () -> {
            new Capability(spec, tempDir.toString());
        });
    }
}
