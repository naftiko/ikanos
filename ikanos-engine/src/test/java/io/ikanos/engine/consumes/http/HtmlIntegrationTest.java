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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.util.Converter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.util.VersionHelper;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for HTML parsing via the converter pipeline. */
public class HtmlIntegrationTest {

    private Capability capability;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/formats/html-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "HTML capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertEquals(schemaVersion, capability.getSpec().getIkanos(), "ikanos version should be " + schemaVersion);
    }

    @Test
    public void testHtmlResponseConversion() throws Exception {
        try (InputStreamReader htmlReader = new InputStreamReader(
                HtmlIntegrationTest.class.getClassLoader().getResourceAsStream("schemas/sample-products.html"),
                StandardCharsets.UTF_8)) {
            assertNotNull(htmlReader, "Sample HTML file should exist in test resources");
            JsonNode root = Converter.convertHtmlToJson(htmlReader, "table.products");

            assertEquals(1, root.get("tables").size());
            assertEquals(2, root.get("tables").get(0).size());
            assertEquals("Widget", root.get("tables").get(0).get(0).get("Name").asText());
            assertEquals("$99", root.get("tables").get(0).get(1).get("Price").asText());
        }
    }
}
