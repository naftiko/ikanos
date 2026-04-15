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
package io.naftiko.spec.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.List;

public class OasYamlWriterTest {

    private OasYamlWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new OasYamlWriter();
    }

    @Test
    void writeYamlShouldProduceValidReparsableOutput() throws Exception {
        OpenAPI original = buildTestOpenApi();

        Path output = tempDir.resolve("test-output.yaml");
        writer.writeYaml(original, output);

        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("openapi:"));
        assertTrue(content.contains("Petstore"));

        // Re-parse and verify
        OpenAPI reparsed = new OpenAPIV3Parser().read(output.toString());
        assertNotNull(reparsed);
        assertEquals("Petstore", reparsed.getInfo().getTitle());
    }

    @Test
    void writeJsonShouldProduceValidReparsableOutput() throws Exception {
        OpenAPI original = buildTestOpenApi();

        Path output = tempDir.resolve("test-output.json");
        writer.writeJson(original, output);

        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("\"openapi\""));
        assertTrue(content.contains("Petstore"));

        // Re-parse and verify
        OpenAPI reparsed = new OpenAPIV3Parser().read(output.toString());
        assertNotNull(reparsed);
        assertEquals("Petstore", reparsed.getInfo().getTitle());
    }

    private OpenAPI buildTestOpenApi() {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info().title("Petstore").version("1.0.0"));
        openApi.setServers(List.of(new Server().url("https://api.petstore.io/v1")));
        openApi.setPaths(new Paths());
        return openApi;
    }

}
