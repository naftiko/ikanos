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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import io.ikanos.Cli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * End-to-end check of #759: {@code ikanos create capability} runs in one non-interactive command,
 * with nothing on stdin, and produces a capability that passes {@code ikanos validate}.
 */
public class CreateCapabilityCommandIntegrationTest {

    @Test
    public void createCapabilityShouldProduceValidFileWithoutAnyInputWhenAllOptionsAreGiven()
            throws Exception {
        String capabilityName = "options-" + UUID.randomUUID().toString().replace("-", "");
        Path yaml = Paths.get(capabilityName + ".ikanos.yaml");
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));

            int createExitCode = new CommandLine(new Cli()).execute("create", "capability",
                    "--name", capabilityName,
                    "--target-uri", "https://api.example.com",
                    "--port", "8081");

            assertEquals(0, createExitCode, "create capability should succeed without stdin");
            assertTrue(Files.exists(yaml), "The capability file should be created");
            assertTrue(Files.readString(yaml).contains("https://api.example.com"));

            int validateExitCode = new CommandLine(new Cli()).execute("validate", yaml.toString());

            assertEquals(0, validateExitCode, "The generated capability should pass validation");
        } finally {
            System.setIn(originalIn);
            Files.deleteIfExists(yaml);
        }
    }
}
