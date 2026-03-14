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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import java.io.File;

public class AvroIntegrationTest {

    private Capability capability;

    @BeforeEach
    public void setUp() throws Exception {
        // Load the Avro capability from test resources
        String resourcePath = "src/test/resources/avro-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "Avro capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        // Initialize capability
        capability = new Capability(spec);
    }

    @Test
    public void testCapabilityAvroFormat() throws Exception {
        var spec = capability.getSpec();
        assertNotNull(spec);

        var exposes = spec.getCapability().getExposes();
        assertEquals(1, exposes.size());

        var consumes = spec.getCapability().getConsumes();
        assertEquals(1, consumes.size());

        var http = (HttpClientSpec) consumes.get(0);
        var resources = http.getResources();
        assertEquals(1, resources.size());
        var operations = resources.get(0).getOperations();
        assertEquals(1, operations.size());

        var operation = operations.get(0);
        assertEquals("avro", operation.getOutputRawFormat());
    }
}
