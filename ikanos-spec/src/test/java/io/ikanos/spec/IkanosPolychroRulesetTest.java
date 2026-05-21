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
package io.ikanos.spec;

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
public class IkanosPolychroRulesetTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    private Path projectRoot;
    private Path rulesetPath;

    @BeforeEach
    public void setUp() {
        projectRoot = Path.of(System.getProperty("user.dir"));
        rulesetPath = projectRoot.resolve("src/main/resources/rules/ikanos-rules.yml");

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
            "src/main/resources/rules/ikanos-rules.yml");

        assertEquals(0, result.exitCode(),
            "Expected valid example to pass Spectral linting.\n" + result.output());
    }

    @Test
    public void testGlobalNamespaceUniquenessAcrossConsumedAndExposedAdapters() throws IOException {
        String duplicatedNamespaces = """
            ikanos: \"0.5\"
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

        Path tmpFile = Files.createTempFile("ikanos-dup-namespace-", ".yml");
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
            assertTrue(result.output().contains("ikanos-namespaces-unique"),
                "Expected lint output to reference ikanos-namespaces-unique.\n"
                    + result.output());
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    public void semanticsConsistencyRuleShouldWarnWhenMcpHintsContradictSemantics()
            throws IOException {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-semantics-inconsistent.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        assertTrue(output.contains("ikanos-aggregate-semantics-consistency"),
            "Expected lint output to reference ikanos-aggregate-semantics-consistency.\n"
                + output);
        assertTrue(output.contains("readOnly=false"),
            "Expected warning about readOnly=false contradicting safe=true.\n"
                + output);
        assertTrue(output.contains("destructive=true"),
            "Expected warning about destructive=true contradicting safe=true.\n"
                + output);
    }

    @Test
    public void semanticsConsistencyRuleShouldWarnWhenRestMethodContradictsSafeSemantics()
            throws IOException {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-semantics-inconsistent.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        assertTrue(output.contains("DELETE"),
            "Expected warning about DELETE contradicting safe=true.\n"
                + output);
    }

    @Test
    public void semanticsConsistencyRuleShouldNotWarnWhenHintsAreConsistent()
            throws IOException {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-semantics-consistent.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        String output = result.output();
        assertTrue(
            !output.contains("ikanos-aggregate-semantics-consistency"),
            "Expected no semantics consistency warnings for consistent document.\n"
                + output);
    }

    // ────────────────────────────────────────────────────────────────
    // §11 — Unified import mechanism rules
    //
    // The fixtures under src/test/resources/imports/ each trigger a single
    // import-related Spectral rule. Each assertion verifies:
    //   1. The lint exits non-zero (rule severity is `error`, except
    //      `ikanos-import-from-not-self` which is `warn` — that test
    //      asserts the rule id appears in output but does not check exit
    //      code).
    //   2. The expected rule id appears in the lint output.
    //
    // See blueprints/unified-import-mechanism.md §11 for the rule catalog
    // and ikanos-rules.yml §6 for the rule definitions.
    // ────────────────────────────────────────────────────────────────

    @Test
    public void standaloneNoImportsRuleShouldFireWhenStandaloneFileContainsImport() {
        assertRuleFires("imports/standalone-with-imports.yaml", "ikanos-standalone-no-imports");
    }

    @Test
    public void importFromRequiredRuleShouldFireWhenImportFieldIsMissing() {
        assertRuleFires("imports/import-missing-fields.yaml", "ikanos-import-from-required");
    }

    @Test
    public void importImportRequiredRuleShouldFireWhenFromFieldIsMissing() {
        assertRuleFires("imports/import-missing-fields.yaml", "ikanos-import-import-required");
    }

    @Test
    public void importUniqueAliasRuleShouldFireWhenTwoImportsShareEffectiveNamespace() {
        assertRuleFires("imports/import-duplicate-alias.yaml", "ikanos-import-unique-alias");
    }

    @Test
    public void importFromNotSelfRuleShouldFireWhenImportFromIsBareDot() {
        ProcessResult result = lintFixture("imports/import-from-self.yaml");
        assertTrue(result.output().contains("ikanos-import-from-not-self"),
            "Expected lint output to reference ikanos-import-from-not-self.\n"
                + result.output());
    }

    @Test
    public void exposesNamespaceRequiredRuleShouldFireForStandaloneExposeMissingNamespace() {
        assertRuleFires("imports/exposes-namespace-missing.yaml",
            "ikanos-exposes-namespace-required");
    }

    @Test
    public void exposesNamespaceRequiredRuleShouldFireForCapabilityExposeMissingNamespace() {
        assertRuleFires("imports/capability-exposes-namespace-missing.yaml",
            "ikanos-exposes-namespace-required");
    }

    @Test
    public void aggregatesNamespaceRequiredRuleShouldFireForStandaloneAggregateMissingNamespace() {
        assertRuleFires("imports/aggregates-namespace-missing.yaml",
            "ikanos-aggregates-namespace-required");
    }

    @Test
    public void aggregatesNamespaceRequiredRuleShouldFireForCapabilityAggregateMissingNamespace() {
        assertRuleFires("imports/capability-aggregates-namespace-missing.yaml",
            "ikanos-aggregates-namespace-required");
    }

    @Test
    public void aggregateFunctionUniqueRuleShouldFireWhenTwoFunctionsShareAName() {
        assertRuleFires("imports/aggregate-duplicate-function-name.yaml",
            "ikanos-aggregates-unique-function-name");
    }

    // ────────────────────────────────────────────────────────────────
    // Reverse-tunnel rules (PR #513)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void tunnelIdentityMustBindRuleShouldFireWhenNamespaceIsUndeclared() {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-tunnel-identity-unbound.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        assertTrue(result.exitCode() != 0,
            "Expected unbound-identity fixture to fail linting.\n" + result.output());
        assertTrue(result.output().contains("ikanos-tunnel-identity-must-bind"),
            "Expected lint output to reference ikanos-tunnel-identity-must-bind.\n"
                + result.output());
    }

    @Test
    public void tunnelIdentityMustBindRuleShouldNotFireWhenBindingIsDeclared() {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-tunnel-identity-bound.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        String output = result.output();
        assertTrue(!output.contains("ikanos-tunnel-identity-must-bind"),
            "Expected no tunnel-identity-must-bind warnings for a correctly bound "
                + "document.\n" + output);
    }

    @Test
    public void tunnelFallbackDirectWarnsRuleShouldFireOnNonLoopbackBaseUri() {
        ProcessResult result = runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/rules/spectral-tunnel-fallback-direct-public.yaml",
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());

        assertTrue(result.output().contains("ikanos-tunnel-fallback-direct-warns"),
            "Expected lint output to reference ikanos-tunnel-fallback-direct-warns.\n"
                + result.output());
    }

    /**
     * Lint a fixture file under {@code src/test/resources/} with the project ruleset.
     */
    private ProcessResult lintFixture(String relativePath) {
        return runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/" + relativePath,
            "--ruleset",
            rulesetPath.toAbsolutePath().toString());
    }

    /**
     * Assert that linting a fixture both exits non-zero and includes the given rule id
     * in its output. Use this for any rule whose severity is {@code error}.
     */
    private void assertRuleFires(String fixturePath, String ruleId) {
        ProcessResult result = lintFixture(fixturePath);
        assertTrue(result.exitCode() != 0,
            "Expected fixture '" + fixturePath + "' to fail linting, but it passed.\n"
                + result.output());
        assertTrue(result.output().contains(ruleId),
            "Expected lint output for '" + fixturePath + "' to reference " + ruleId + ".\n"
                + result.output());
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
