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
package io.ikanos.engine.imports;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link SourceFileLoader}: caching, both root-section and
 * capability-wrapped forms, absolute vs. relative paths.
 */
class SourceFileLoaderTest {

    private SourceFileLoader loader;
    private Path tempDir;
    private String schemaVersion;

    @BeforeEach
    void setUp() throws IOException {
        loader = new SourceFileLoader();
        tempDir = Files.createTempDirectory("ikanos-source-loader-test-");
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try { Files.delete(path); } catch (IOException e) { /* ignore */ }
                });
    }

    @Test
    void loadShouldParseStandaloneConsumesFile() throws Exception {
        String yaml = """
                ikanos: "%s"
                consumes:
                  - type: "http"
                    namespace: "api"
                    baseUri: "https://api.example.com"
                    resources: []
                """.formatted(schemaVersion);
        Path file = tempDir.resolve("api.consumes.yml");
        Files.writeString(file, yaml);

        IkanosSpec spec = loader.load(file);

        assertNotNull(spec);
        assertNotNull(spec.getConsumes());
        assertFalse(spec.getConsumes().isEmpty());
    }

    @Test
    void loadShouldCacheByAbsolutePath() throws Exception {
        String yaml = """
                ikanos: "%s"
                aggregates:
                  - namespace: "agg"
                    display: "Test"
                    flows: []
                """.formatted(schemaVersion);
        Path file = tempDir.resolve("agg.aggregates.yml");
        Files.writeString(file, yaml);

        IkanosSpec first = loader.load(file);
        IkanosSpec second = loader.load(file);

        assertSame(first, second, "Repeated load should return cached instance");
        assertEquals(1, loader.cacheSize());
    }

    @Test
    void loadShouldThrowForNonexistentFile() {
        Path missing = tempDir.resolve("nonexistent.yml");

        assertThrows(IOException.class, () -> loader.load(missing));
    }

    @Test
    void loadShouldThrowForMalformedYaml() throws Exception {
        Path bad = tempDir.resolve("bad.yml");
        Files.writeString(bad, "this is not valid yaml: [[[unterminated\n  - broken: {");

        assertThrows(IOException.class, () -> loader.load(bad));
    }

    @Test
    void loadShouldParseCapabilityWrappedForm() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "wrapped"
                      baseUri: "https://wrapped.example.com"
                      resources: []
                  exposes:
                    - type: "rest"
                      namespace: "rest-svc"
                      address: "localhost"
                      port: 8080
                      resources: []
                """.formatted(schemaVersion);
        Path file = tempDir.resolve("cap.yml");
        Files.writeString(file, yaml);

        IkanosSpec spec = loader.load(file);

        assertNotNull(spec.getCapability());
        assertFalse(spec.getCapability().getConsumes().isEmpty());
    }

    @Test
    void loadShouldParseTwoDistinctFiles() throws Exception {
        String yaml1 = """
                ikanos: "%s"
                consumes:
                  - type: "http"
                    namespace: "api-1"
                    baseUri: "https://one.example.com"
                    resources: []
                """.formatted(schemaVersion);
        String yaml2 = """
                ikanos: "%s"
                consumes:
                  - type: "http"
                    namespace: "api-2"
                    baseUri: "https://two.example.com"
                    resources: []
                """.formatted(schemaVersion);
        Path file1 = tempDir.resolve("one.consumes.yml");
        Path file2 = tempDir.resolve("two.consumes.yml");
        Files.writeString(file1, yaml1);
        Files.writeString(file2, yaml2);

        IkanosSpec spec1 = loader.load(file1);
        IkanosSpec spec2 = loader.load(file2);

        assertNotSame(spec1, spec2);
        assertEquals(2, loader.cacheSize());
    }
}
