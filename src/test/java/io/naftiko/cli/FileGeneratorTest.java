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
import io.naftiko.cli.enums.FileFormat;

public class FileGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void generateCapabilityFileShouldRenderYamlAndJsonTemplates() throws Exception {
        String capabilityName = "orders-" + UUID.randomUUID().toString().replace("-", "");
        Path yaml = Paths.get(capabilityName + ".capability.yaml");
        Path json = Paths.get(capabilityName + ".capability.json");
        try {
            FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML,
                    "https://api.example.com", "8080");
            FileGenerator.generateCapabilityFile(capabilityName, FileFormat.JSON,
                    "https://api.example.com", "8080");
            assertTrue(Files.exists(yaml));
            assertTrue(Files.exists(json));

            String yamlContent = Files.readString(yaml);
            String jsonContent = Files.readString(json);

            assertTrue(yamlContent.contains(capabilityName));
            assertTrue(yamlContent.contains("https://api.example.com"));
            assertTrue(yamlContent.contains("8080"));
            assertTrue(yamlContent.contains("{{path}}"));

            assertTrue(jsonContent.contains(capabilityName));
            assertTrue(jsonContent.contains("https://api.example.com"));
            assertTrue(jsonContent.contains("8080"));
        } finally {
            Files.deleteIfExists(yaml);
            Files.deleteIfExists(json);
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