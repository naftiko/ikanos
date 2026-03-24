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
package io.naftiko.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Optional end-to-end tests for the Spectral ruleset.
 * <p>
 * These tests are skipped automatically when Node.js/NPX/Spectral CLI are not available,
 * so Java-only contributor environments are not blocked.
 */
public class NaftikoSpectralRulesetTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    private Path projectRoot;
    private Path rulesetPath;

    @BeforeEach
    public void setUp() {
        projectRoot = Path.of(System.getProperty("user.dir"));
        rulesetPath = projectRoot.resolve("src/main/resources/schemas/naftiko-rules.yml");

        Assumptions.assumeTrue(Files.exists(rulesetPath),
            "Skipping Spectral tests: ruleset file not found at " + rulesetPath);
        Assumptions.assumeTrue(isCommandAvailable("node", "--version"),
            "Skipping Spectral tests: Node.js is not available on PATH");
        Assumptions.assumeTrue(isCommandAvailable("npx", "--version"),
            "Skipping Spectral tests: NPX is not available on PATH");

        ProcessResult spectralVersion = runCommand("npx", "@stoplight/spectral-cli", "--version");
        Assumptions.assumeTrue(spectralVersion.exitCode() == 0,
            "Skipping Spectral tests: Spectral CLI is not available via npx\n"
                + spectralVersion.output());
    }

    @Test
    public void testRulesetAcceptsValidExample() {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/main/resources/schemas/examples/cir.yml",
            "--ruleset",
            "src/main/resources/schemas/naftiko-rules.yml");

        assertEquals(0, result.exitCode(),
            "Expected valid example to pass Spectral linting.\n" + result.output());
    }

    @Test
    public void testGlobalNamespaceUniquenessAcrossConsumedAndExposedAdapters() throws IOException {
        String duplicatedNamespaces = """
            naftiko: \"0.5\"
            consumes:
              - type: http
                namespace: shared
                baseUri: https://api.example.com
                resources:
                  - name: ping
                    path: /ping
                    operations:
                      - name: get-ping
                        method: GET
            capability:
              exposes:
                - type: rest
                  port: 8080
                  namespace: shared
                  resources:
                    - path: /health
                      operations:
                        - method: GET
                          outputParameters:
                            - type: string
                              const: ok
            """;

        Path tmpFile = Files.createTempFile("naftiko-dup-namespace-", ".yml");
        try {
            Files.writeString(tmpFile, duplicatedNamespaces, StandardCharsets.UTF_8);

            ProcessResult result = runCommand(
                "npx",
                "@stoplight/spectral-cli",
                "lint",
                tmpFile.toAbsolutePath().toString(),
                "--ruleset",
                rulesetPath.toAbsolutePath().toString());

            assertTrue(result.exitCode() != 0,
                "Expected duplicate namespace document to fail linting, but it passed.\n"
                    + result.output());
            assertTrue(result.output().contains("naftiko-namespaces-unique"),
                "Expected lint output to reference naftiko-namespaces-unique.\n"
                    + result.output());
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private boolean isCommandAvailable(String command, String arg) {
        ProcessResult result = runCommand(command, arg);
        return result.exitCode() == 0;
    }

    private ProcessResult runCommand(String... args) {
        List<String> command = new ArrayList<>();
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            boolean finished = p.waitFor(PROCESS_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String output;
            try (InputStream is = p.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!finished) {
                p.destroyForcibly();
                return new ProcessResult(124, output + "\n[timeout waiting for process]");
            }

            return new ProcessResult(p.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(1, "Process execution failed: " + e.getMessage());
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
