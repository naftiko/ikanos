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
package io.naftiko.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.naftiko.spec.exposes.McpServerResourceSpec;

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
                List.of(staticResource("docs", "data://docs", docs)), null);

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
                List.of(staticResource("docs", "data://docs", docs)), null);

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
                List.of(staticResource("docs", "data://docs", docs)), null);

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

    private static McpServerResourceSpec staticResource(String name, String uri, Path dir) {
        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName(name);
        spec.setUri(uri);
        spec.setLocation(dir.toUri().toString());
        return spec;
    }
}