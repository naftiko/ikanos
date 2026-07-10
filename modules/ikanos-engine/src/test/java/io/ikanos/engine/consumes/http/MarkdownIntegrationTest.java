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
import io.ikanos.spec.util.VersionHelper;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for Markdown parsing via the converter pipeline. */
public class MarkdownIntegrationTest {

    private Capability capability;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/formats/markdown-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Markdown capability test file should exist at " + resourcePath);

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
    public void testMarkdownResponseConversion() throws Exception {
        try (InputStreamReader markdownReader = new InputStreamReader(
                MarkdownIntegrationTest.class.getClassLoader().getResourceAsStream("schemas/sample-readme.md"),
                StandardCharsets.UTF_8)) {
            assertNotNull(markdownReader, "Sample Markdown file should exist in test resources");
            JsonNode root = Converter.convertMarkdownToJson(markdownReader);

            assertEquals("Product Release Notes", root.get("frontMatter").get("title").asText());
            assertEquals(1, root.get("tables").size());
            assertEquals("GA", root.get("tables").get(0).get(0).get("Status").asText());
            assertEquals("Overview", root.get("sections").get(0).get("heading").asText());
        }
    }
}
