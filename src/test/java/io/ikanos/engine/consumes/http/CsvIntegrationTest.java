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
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.util.VersionHelper;
import java.io.File;
import java.io.InputStream;
import com.fasterxml.jackson.databind.MappingIterator;

public class CsvIntegrationTest {

    private Capability capability;
    private ObjectMapper jsonMapper;
    private CsvMapper csvMapper;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        // Load the CSV capability from test resources
        String resourcePath = "src/test/resources/formats/csv-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "CSV capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        // Initialize capability
        capability = new Capability(spec);
        jsonMapper = new ObjectMapper();
        csvMapper = new CsvMapper();
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertNotNull(capability.getSpec(), "Capability spec should be loaded");
        assertEquals(schemaVersion, capability.getSpec().getIkanos(), "ikanos version should be " + schemaVersion);
    }

    @Test
    public void testCsvResponseConversion() throws Exception {
        InputStream csvStream = CsvIntegrationTest.class.getClassLoader()
                .getResourceAsStream("schemas/sample-users.csv");
        assertNotNull(csvStream, "Sample CSV file should exist in test resources");

        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        MappingIterator<JsonNode> it =
                csvMapper.readerFor(JsonNode.class).with(schema).readValues(csvStream);

        ArrayNode arr = jsonMapper.createArrayNode();
        while (it.hasNext()) {
            arr.add(it.next());
        }

        assertEquals(3, arr.size(), "Should have 3 users");

        JsonNode first = arr.get(0);
        assertEquals("1", first.get("id").asText(), "First user ID should be 1");
        assertEquals("Alice Smith", first.get("name").asText(),
                "First user name should be Alice Smith");
        assertEquals("alice@example.com", first.get("email").asText(),
                "First user email should be alice@example.com");
    }
}
