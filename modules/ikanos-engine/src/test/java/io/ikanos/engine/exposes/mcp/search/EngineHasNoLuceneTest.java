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
package io.ikanos.engine.exposes.mcp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps Apache Lucene out of the engine. Tool search lives in the optional
 * {@code ikanos-tool-search} module, reached only through {@link ToolSearchIndexFactory} and
 * {@link java.util.ServiceLoader}, so an application that embeds the engine and has its own tool
 * search never gets a second Lucene.
 */
class EngineHasNoLuceneTest {

    private static final Path CLASSES = Path.of("target/classes");

    @Test
    @DisplayName("No compiled engine class references Lucene or the tool search module")
    void engineClassesShouldNotReferenceLuceneOrTheModule() throws IOException {
        assertTrue(Files.isDirectory(CLASSES), "run after compilation: " + CLASSES);
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(CLASSES)) {
            offenders = files.filter(path -> path.toString().endsWith(".class"))
                    .filter(EngineHasNoLuceneTest::referencesLuceneOrModule)
                    .toList();
        }
        assertEquals(List.of(), offenders);
    }

    private static boolean referencesLuceneOrModule(Path classFile) {
        try {
            String constants = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return constants.contains("org/apache/lucene/") || constants.contains("io/ikanos/toolsearch/");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
