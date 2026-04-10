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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.naftiko.engine.ConsumesImportResolver;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.consumes.ImportedConsumesHttpSpec;
import io.naftiko.util.VersionHelper;

/**
 * Unit tests for ConsumesImportResolver.
 * Tests import loading, namespace resolution, and alias handling.
 */
public class ConsumesImportResolverTest {

    private ConsumesImportResolver resolver;
    private Path tempDir;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws IOException {
        resolver = new ConsumesImportResolver();
        tempDir = Files.createTempDirectory("naftiko-import-test-");
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean up temp files
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
    public void testResolveSimpleImport() throws Exception {
        // Create source consumes file
        String sourceConsumesYaml = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "notion"
                baseUri: "https://api.notion.com"
                resources: []
            """.formatted(schemaVersion);

        Path sourcePath = tempDir.resolve("notion-consumes.yml");
        Files.writeString(sourcePath, sourceConsumesYaml);

        // Create capability consumes with import
        List<ClientSpec> consumes = new ArrayList<>();
        consumes.add(new ImportedConsumesHttpSpec(
            "./notion-consumes.yml",
            "notion",
            null  // no alias
        ));

        // Resolve
        resolver.resolveImports(consumes, tempDir.toString());

        // Verify
        assertEquals(1, consumes.size());
        assertTrue(consumes.get(0) instanceof HttpClientSpec);

        HttpClientSpec resolved = (HttpClientSpec) consumes.get(0);
        assertEquals("notion", resolved.getNamespace());
        assertEquals("https://api.notion.com", resolved.getBaseUri());
    }

    @Test
    public void testResolveImportWithAlias() throws Exception {
        // Create two source consumes with same namespace
        String sourceEn = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "hello-world"
                baseUri: "http://localhost:8080"
                resources: []
            """.formatted(schemaVersion);

        String sourceFr = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "hello-world"
                baseUri: "http://localhost:8081"
                resources: []
            """.formatted(schemaVersion);

        Files.writeString(tempDir.resolve("hello-en.yml"), sourceEn);
        Files.writeString(tempDir.resolve("hello-fr.yml"), sourceFr);

        // Create imports with aliases
        List<ClientSpec> consumes = new ArrayList<>();
        consumes.add(new ImportedConsumesHttpSpec("./hello-en.yml", "hello-world", "hello-en"));
        consumes.add(new ImportedConsumesHttpSpec("./hello-fr.yml", "hello-world", "hello-fr"));

        resolver.resolveImports(consumes, tempDir.toString());

        // Verify
        assertEquals(2, consumes.size());
        assertEquals("hello-en", ((HttpClientSpec) consumes.get(0)).getNamespace());
        assertEquals("hello-fr", ((HttpClientSpec) consumes.get(1)).getNamespace());
    }

    @Test
    public void testResolveImportFileNotFound() {
        List<ClientSpec> consumes = new ArrayList<>();
        consumes.add(new ImportedConsumesHttpSpec("./nonexistent.yml", "api", null));

        assertThrows(IOException.class, () -> {
            resolver.resolveImports(consumes, tempDir.toString());
        });
    }

    @Test
    public void testResolveImportNamespaceNotFound() throws Exception {
        String sourceYaml = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "api-v1"
                baseUri: "http://api.example.com"
                resources: []
            """.formatted(schemaVersion);

        Files.writeString(tempDir.resolve("api.yml"), sourceYaml);

        List<ClientSpec> consumes = new ArrayList<>();
        // Try to import "api-v2" which doesn't exist
        consumes.add(new ImportedConsumesHttpSpec("./api.yml", "api-v2", null));

        IOException ex = assertThrows(IOException.class, () -> {
            resolver.resolveImports(consumes, tempDir.toString());
        });

        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void testMixedImportsAndInlineConsumes() throws Exception {
        String sourceYaml = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "external-api"
                baseUri: "http://external.com"
                resources: []
            """.formatted(schemaVersion);

        Files.writeString(tempDir.resolve("external.yml"), sourceYaml);

        List<ClientSpec> consumes = new ArrayList<>();

        // Inline consumes
        HttpClientSpec inline = new HttpClientSpec();
        inline.setNamespace("local-api");
        inline.setBaseUri("http://localhost:9999");
        consumes.add(inline);

        // Imported consumes
        consumes.add(new ImportedConsumesHttpSpec("./external.yml", "external-api", null));

        resolver.resolveImports(consumes, tempDir.toString());

        // Both should be HttpClientSpec, in same order
        assertEquals(2, consumes.size());
        assertTrue(consumes.get(0) instanceof HttpClientSpec);
        assertTrue(consumes.get(1) instanceof HttpClientSpec);
        assertEquals("local-api", ((HttpClientSpec) consumes.get(0)).getNamespace());
        assertEquals("external-api", ((HttpClientSpec) consumes.get(1)).getNamespace());
    }

    @Test
    public void testRelativePathResolution() throws Exception {
        // Create nested directory structure
        Path subdir = tempDir.resolve("adapters");
        Files.createDirectory(subdir);

        String sourceYaml = """
            naftiko: "%s"
            consumes:
              - type: "http"
                namespace: "nested"
                baseUri: "http://example.com"
                resources: []
            """.formatted(schemaVersion);

        Files.writeString(subdir.resolve("nested.yml"), sourceYaml);

        // Create capability in parent with relative path
        List<ClientSpec> consumes = new ArrayList<>();
        consumes.add(new ImportedConsumesHttpSpec("./adapters/nested.yml", "nested", null));

        resolver.resolveImports(consumes, tempDir.toString());

        assertEquals(1, consumes.size());
        assertEquals("nested", ((HttpClientSpec) consumes.get(0)).getNamespace());
    }

    @Test
    public void testEmptyConsumesListIsNoop() throws Exception {
        List<ClientSpec> consumes = new ArrayList<>();

        // Should not throw
        resolver.resolveImports(consumes, tempDir.toString());

        assertEquals(0, consumes.size());
    }

    @Test
    public void testNullConsumesListIsNoop() throws Exception {
        // Should not throw
        resolver.resolveImports(null, tempDir.toString());
    }
}
