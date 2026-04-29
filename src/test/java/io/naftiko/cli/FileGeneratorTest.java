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
package io.naftiko.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void generateCapabilityFileShouldRenderYamlTemplates() throws Exception {
        String capabilityName = "orders-" + UUID.randomUUID().toString().replace("-", "");
        Path yaml = Paths.get(capabilityName + ".naftiko.yaml");
        try {
            FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML,
                    "https://api.example.com", "8080");
            assertTrue(Files.exists(yaml));

            String yamlContent = Files.readString(yaml);

            assertTrue(yamlContent.startsWith("# @naftiko\n---\n"), "Output should start with Naftiko modeline header");
            assertTrue(yamlContent.contains(capabilityName));
            assertTrue(yamlContent.contains("https://api.example.com"));
            assertTrue(yamlContent.contains("8080"));
            assertTrue(yamlContent.contains("{{path}}"));
        } finally {
            Files.deleteIfExists(yaml);
        }
    }

    @Test
    public void generateCapabilityFileShouldFailForUnknownTemplate() {
        FileNotFoundException error = assertThrows(FileNotFoundException.class,
                () -> FileGenerator.generateCapabilityFile("orders", FileFormat.UNKNOWN,
                        "https://api.example.com", "8080"));

        assertEquals("Template not found: templates/capability.unknown.mustache",
                error.getMessage());
    }
}