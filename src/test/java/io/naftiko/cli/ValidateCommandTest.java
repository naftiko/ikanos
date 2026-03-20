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
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;

public class ValidateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    public void loadFileShouldParseYamlAndJson() throws Exception {
        Path yaml = tempDir.resolve("sample.yaml");
        Files.writeString(yaml, "name: test\n");

        Path json = tempDir.resolve("sample.json");
        Files.writeString(json, "{\"name\":\"test\"}\n");

        ValidateCommand command = new ValidateCommand();
        Method loadFile = ValidateCommand.class.getDeclaredMethod("loadFile", File.class);
        loadFile.setAccessible(true);

        JsonNode yamlNode = (JsonNode) loadFile.invoke(command, yaml.toFile());
        JsonNode jsonNode = (JsonNode) loadFile.invoke(command, json.toFile());

        assertEquals("test", yamlNode.path("name").asText());
        assertEquals("test", jsonNode.path("name").asText());
    }

    @Test
    public void loadFileShouldRejectUnsupportedExtensions() throws Exception {
        Path txt = tempDir.resolve("sample.txt");
        Files.writeString(txt, "content\n");

        ValidateCommand command = new ValidateCommand();
        Method loadFile = ValidateCommand.class.getDeclaredMethod("loadFile", File.class);
        loadFile.setAccessible(true);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> loadFile.invoke(command, txt.toFile()));

        assertEquals(IllegalArgumentException.class, error.getCause().getClass());
        assertEquals("Unsupported file format. Only .yaml, .yml, and .json are supported.",
                error.getCause().getMessage());
    }
}