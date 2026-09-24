/*
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ikanos.spec.util.VersionHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidateCommandIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void validateShouldSucceedWithoutWarningsWhenCapabilityIsValid() throws Exception {
        Path capability = Path.of("..", "ikanos-docs", "tutorial", "step-1-shipyard-mock.yml");

        Result result = validate(capability);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Validation successful"), result.output());
        assertFalse(result.output().contains("WARN"), result.output());
        assertFalse(result.output().contains("Unknown keyword name"), result.output());
    }

    @Test
    void validateShouldReportErrorsWithoutKeywordWarningWhenCapabilityIsInvalid() throws Exception {
        Path capability = tempDir.resolve("invalid.yaml");
        Files.writeString(capability, "ikanos: \"%s\"\ninfo: {}\n"
                .formatted(VersionHelper.getSchemaVersion()));

        Result result = validate(capability);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Validation failed"), result.output());
        assertTrue(result.output().contains("required"), result.output());
        assertTrue(result.output().contains("Path: $"), result.output());
        assertFalse(result.output().contains("Unknown keyword name"), result.output());
    }

    private Result validate(Path capability) throws Exception {
        Path output = tempDir.resolve("output.txt");
        // networknt remembers unknown keywords globally, so use a fresh JVM for each CLI run.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"),
                "io.ikanos.Cli", "validate", capability.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Validation timed out");
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private record Result(int exitCode, String output) {}
}
