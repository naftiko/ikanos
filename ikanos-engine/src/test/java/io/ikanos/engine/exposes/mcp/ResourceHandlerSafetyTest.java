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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;

public class ResourceHandlerSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    public void readShouldRejectPathTraversalSegments() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("readme.md"), "hello\n");
        Files.writeString(tempDir.resolve("secrets.txt"), "do-not-read\n");

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("docs", staticResource("docs", "data://docs", docs)), null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handler.read("data://docs/../secrets.txt"));
        assertEquals("Path traversal detected for resource URI: data://docs/../secrets.txt",
                error.getMessage());
    }

    @Test
    public void readShouldRejectDirectoryAsConcreteTarget() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("docs", staticResource("docs", "data://docs", docs)), null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handler.read("data://docs"));
        assertEquals("Resource URI resolves to a directory, not a file: data://docs",
                error.getMessage());
    }

    @Test
    public void readShouldReturnTextAndDetectedMimeTypeForMarkdown() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("guide.md"), "# guide\n");

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("docs", staticResource("docs", "data://docs", docs)), null);

        List<ResourceHandler.ResourceContent> content = handler.read("data://docs/guide.md");

        assertEquals(1, content.size());
        assertEquals("text/markdown", content.get(0).mimeType);
        assertEquals("# guide\n", content.get(0).text);
    }

    @Test
    public void matchTemplateShouldNotMatchAcrossSlashBoundaries() {
        Map<String, String> params = ResourceHandler.matchTemplate(
                "data://users/{userId}/profile", "data://users/a/b/profile");

        assertEquals(null, params);
    }

        @Test
        public void listAllShouldExpandStaticFilesAndListTemplatesShouldReturnOnlyTemplates()
                        throws Exception {
            Path docs = tempDir.resolve("docs");
            Files.createDirectories(docs);
            Files.writeString(docs.resolve("guide.md"), "# guide\n");
            Files.writeString(docs.resolve("data.json"), "{}\n");

            McpServerResourceSpec staticSpec = staticResource("docs", "data://docs", docs);

            McpServerResourceSpec templateSpec = new McpServerResourceSpec();
            templateSpec.setName("user-profile");
            templateSpec.setUri("data://users/{userId}/profile");

            java.util.LinkedHashMap<String, McpServerResourceSpec> specMap = new java.util.LinkedHashMap<>();
            specMap.put(staticSpec.getName(), staticSpec);
            specMap.put(templateSpec.getName(), templateSpec);
            ResourceHandler handler = new ResourceHandler(null, specMap, null);

            List<Map<String, String>> listed = handler.listAll();
            assertEquals(2, listed.size());
            assertTrue(listed.stream().anyMatch(e -> "data://docs/guide.md".equals(e.get("uri"))));
            assertTrue(listed.stream().anyMatch(e -> "data://docs/data.json".equals(e.get("uri"))));

            List<McpServerResourceSpec> templates = handler.listTemplates();
            assertEquals(1, templates.size());
            assertEquals("user-profile", templates.get(0).getName());
        }

        @Test
        public void readShouldThrowForUnknownResourceUri() {
            ResourceHandler handler = new ResourceHandler(null, Map.of(), null);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> handler.read("data://unknown"));
            assertEquals("Unknown resource URI: data://unknown", error.getMessage());
        }

        @Test
        public void matchTemplateShouldHandleNullsAndExactNonTemplateUris() {
            assertEquals(null, ResourceHandler.matchTemplate(null, "data://x"));
            assertEquals(null, ResourceHandler.matchTemplate("data://x", null));

            Map<String, String> exact = ResourceHandler.matchTemplate("data://fixed", "data://fixed");
            assertNotNull(exact);
            assertTrue(exact.isEmpty());

            Map<String, String> mismatch = ResourceHandler.matchTemplate("data://fixed",
                    "data://other");
            assertEquals(null, mismatch);
            assertFalse(mismatch != null);
        }

    private static McpServerResourceSpec staticResource(String name, String uri, Path dir) {
        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName(name);
        spec.setUri(uri);
        spec.setLocation(dir.toUri().toString());
        return spec;
    }
}
