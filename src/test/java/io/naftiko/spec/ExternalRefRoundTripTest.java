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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ExternalRefSpec round-trip serialization/deserialization.
 * Validates that external reference metadata is preserved during read/write cycles.
 */
public class ExternalRefRoundTripTest {

    @Test
    public void testFileExternalRef() throws Exception {
        String yaml = """
                externalRefs:
                  - name: notion-config
                    description: Notion API configuration
                    type: json
                    resolution: file
                    uri: config/notion.json
                    keys:
                      notion_token: NOTION_INTEGRATION_TOKEN
                      notion_database: DATABASE_ID
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        // Deserialize from YAML
        NaftikoSpec original = yamlMapper.readValue(yaml, NaftikoSpec.class);
        assertNotNull(original.getExternalRefs());
        assertEquals(1, original.getExternalRefs().size());

        ExternalRefSpec extRef = original.getExternalRefs().get(0);
        assertEquals("notion-config", extRef.getName());
        assertEquals("Notion API configuration", extRef.getDescription());
        assertEquals("json", extRef.getType());
        assertEquals("file", extRef.getResolution());
        assertTrue(extRef instanceof FileExternalRefSpec);

        FileExternalRefSpec fileRef = (FileExternalRefSpec) extRef;
        assertEquals("config/notion.json", fileRef.getUri());
        assertEquals(2, fileRef.getKeys().size());
        assertEquals("NOTION_INTEGRATION_TOKEN", fileRef.getKeys().get("notion_token"));
        assertEquals("DATABASE_ID", fileRef.getKeys().get("notion_database"));

        // Serialize to JSON
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        
        // Deserialize back from JSON
        NaftikoSpec roundTrip = jsonMapper.readValue(serialized, NaftikoSpec.class);
        
        // Validate round-trip
        assertNotNull(roundTrip.getExternalRefs());
        assertEquals(1, roundTrip.getExternalRefs().size());
        
        ExternalRefSpec rtExtRef = roundTrip.getExternalRefs().get(0);
        assertEquals("notion-config", rtExtRef.getName());
        assertEquals("Notion API configuration", rtExtRef.getDescription());
        assertEquals("json", rtExtRef.getType());
        assertEquals("file", rtExtRef.getResolution());
        assertTrue(rtExtRef instanceof FileExternalRefSpec);
        
        FileExternalRefSpec rtFileRef = (FileExternalRefSpec) rtExtRef;
        assertEquals("config/notion.json", rtFileRef.getUri());
        assertEquals(2, rtFileRef.getKeys().size());
        assertEquals("NOTION_INTEGRATION_TOKEN", rtFileRef.getKeys().get("notion_token"));
    }

    @Test
    public void testRuntimeExternalRef() throws Exception {
        String yaml = """
                externalRefs:
                  - name: aws-credentials
                    description: AWS configuration from environment
                    type: json
                    resolution: runtime
                    keys:
                      aws_access_key: AWS_ACCESS_KEY_ID
                      aws_secret_key: AWS_SECRET_ACCESS_KEY
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        // Deserialize from YAML
        NaftikoSpec original = yamlMapper.readValue(yaml, NaftikoSpec.class);
        assertEquals(1, original.getExternalRefs().size());

        ExternalRefSpec extRef = original.getExternalRefs().get(0);
        assertEquals("aws-credentials", extRef.getName());
        assertEquals("runtime", extRef.getResolution());
        assertTrue(extRef instanceof RuntimeExternalRefSpec);
        assertEquals(2, extRef.getKeys().size());

        // Serialize to JSON
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        
        // Deserialize back from JSON
        NaftikoSpec roundTrip = jsonMapper.readValue(serialized, NaftikoSpec.class);
        
        // Validate round-trip
        assertEquals(1, roundTrip.getExternalRefs().size());
        ExternalRefSpec rtExtRef = roundTrip.getExternalRefs().get(0);
        assertEquals("aws-credentials", rtExtRef.getName());
        assertEquals("runtime", rtExtRef.getResolution());
        assertTrue(rtExtRef instanceof RuntimeExternalRefSpec);
    }

    @Test
    public void testMultipleExternalRefs() throws Exception {
        String yaml = """
                externalRefs:
                  - name: notion-config
                    description: Notion configuration
                    type: json
                    resolution: file
                    uri: config/notion.json
                    keys:
                      token: NOTION_TOKEN
                  - name: github-env
                    description: GitHub environment secrets
                    type: json
                    resolution: runtime
                    keys:
                      gh_token: GITHUB_TOKEN
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        NaftikoSpec original = yamlMapper.readValue(yaml, NaftikoSpec.class);
        assertEquals(2, original.getExternalRefs().size());
        
        ExternalRefSpec ref1 = original.getExternalRefs().get(0);
        ExternalRefSpec ref2 = original.getExternalRefs().get(1);
        
        assertEquals("notion-config", ref1.getName());
        assertTrue(ref1 instanceof FileExternalRefSpec);
        
        assertEquals("github-env", ref2.getName());
        assertTrue(ref2 instanceof RuntimeExternalRefSpec);

        // Serialize and roundtrip
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        NaftikoSpec roundTrip = jsonMapper.readValue(serialized, NaftikoSpec.class);
        
        assertEquals(2, roundTrip.getExternalRefs().size());
        assertEquals("notion-config", roundTrip.getExternalRefs().get(0).getName());
        assertEquals("github-env", roundTrip.getExternalRefs().get(1).getName());
    }

    @Test
    public void testEmptyExternalRefs() throws Exception {
        String yaml = """
                naftiko: "0.4"
                info:
                  label: Test Capability
                  description: Test
                capability:
                  exposes: []
                  consumes: []
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        NaftikoSpec original = yamlMapper.readValue(yaml, NaftikoSpec.class);
        assertNotNull(original.getExternalRefs());
        assertTrue(original.getExternalRefs().isEmpty());

        // Serialize and verify empty array is omitted or present (both valid per @JsonInclude)
        String serialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        NaftikoSpec roundTrip = jsonMapper.readValue(serialized, NaftikoSpec.class);
        
        assertNotNull(roundTrip.getExternalRefs());
    }

    @Test
    public void testFileExternalRefWithoutUri() throws Exception {
        String yaml = """
                externalRefs:
                  - name: local-config
                    description: Configuration
                    type: json
                    resolution: file
                    keys:
                      api_key: API_KEY
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        // Should not throw; uri is optional on FileExternalRefSpec
        NaftikoSpec spec = yamlMapper.readValue(yaml, NaftikoSpec.class);
        assertNotNull(spec.getExternalRefs());
        assertEquals(1, spec.getExternalRefs().size());
        
        FileExternalRefSpec fileRef = (FileExternalRefSpec) spec.getExternalRefs().get(0);
        assertNull(fileRef.getUri());
        assertEquals(1, fileRef.getKeys().size());
    }

}
