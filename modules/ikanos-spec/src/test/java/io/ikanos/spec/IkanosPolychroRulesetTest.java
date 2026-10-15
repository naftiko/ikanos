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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            "src/main/resources/schemas/examples/reverse-tunnel-ziti.yml",
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
            rulesetPath.toAbsolutePath().toString(),
            "--fail-severity",
            "warn");

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        assertTrue(output.contains("ikanos-aggregate-semantics-consistency"),
            "Expected lint output to reference ikanos-aggregate-semantics-consistency.\n"
                + output);
        // The Spectral CLI compact output only includes the rule-level message and JSON
        // pointer, not the per-finding custom message from the JS function. We assert
        // the rule fired on both contradicting hints (readOnly and destructive) by
        // checking the path components Spectral emits.
        assertTrue(output.contains("hints.readOnly"),
            "Expected warning targeting hints.readOnly (contradicting safe=true).\n"
                + output);
        assertTrue(output.contains("hints.destructive"),
            "Expected warning targeting hints.destructive (contradicting safe=true).\n"
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
            rulesetPath.toAbsolutePath().toString(),
            "--fail-severity",
            "warn");

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        // The REST contradiction reports against the resource operation; assert the
        // rule fires on a REST exposes adapter path. The original assertion checked
        // for the literal "DELETE" method name, which the Spectral CLI compact
        // output does not include (only the rule message + JSON pointer).
        assertTrue(output.contains("exposes") && output.contains("operations"),
            "Expected warning targeting a REST exposes operation contradicting "
                + "safe semantics.\n" + output);
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
    public void exposesNamespaceRequiredRuleShouldNotFireForStandaloneControlExpose() {
        ProcessResult result = lintFixture("imports/exposes-control-without-namespace.yaml");
        assertEquals(0, result.exitCode(),
            "Expected the fixture to lint cleanly.\n" + result.output());
        assertFalse(result.output().contains("ikanos-exposes-namespace-required"),
            "Expected no ikanos-exposes-namespace-required error for a standalone control "
                + "adapter.\n" + result.output());
    }

    @Test
    public void exposesNamespaceRequiredRuleShouldNotFireForCapabilityControlExpose() {
        ProcessResult result = lintFixture(
            "imports/capability-exposes-control-without-namespace.yaml");
        assertEquals(0, result.exitCode(),
            "Expected the fixture to lint cleanly.\n" + result.output());
        assertFalse(result.output().contains("ikanos-exposes-namespace-required"),
            "Expected no ikanos-exposes-namespace-required error for a control adapter.\n"
                + result.output());
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

    // ────────────────────────────────────────────────────────────────
    // MCP trust rules (#483)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireWhenEveryAddressIsTrusted() {
        ProcessResult result = lintFixture("rules/spectral-mcp-trust-broad-network.yaml");
        assertTrue(result.output().contains("ikanos-mcp-trust-network-too-broad"),
            "Expected 0.0.0.0/0 and ::/0 in trustedNetworks to be flagged (#483).\n"
                + result.output());
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldNotFireOnScopedNetworks() {
        ProcessResult result = lintFixture("rules/spectral-mcp-trust-networks-scoped.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-network-too-broad"),
            "Expected scoped private networks not to be flagged.\n" + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireWhenAdapterListensBeyondLoopback() {
        ProcessResult result =
            lintFixture("rules/spectral-mcp-trust-loopback-public-address.yaml");
        assertTrue(result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected trusted loopback peers on a 0.0.0.0 adapter to be flagged (#483).\n"
                + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldNotFireWhenAdapterListensOnLoopback() {
        ProcessResult result =
            lintFixture("rules/spectral-mcp-trust-loopback-local-address.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected trusted loopback peers on a loopback adapter not to be flagged.\n"
                + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldNotFireOnScopedNetworks() {
        ProcessResult result = lintFixture("rules/spectral-mcp-trust-networks-scoped.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected scoped private networks not to be flagged.\n" + result.output());
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireOnTheIpv6WholeAddressSpace() {
        assertWarnsOn("rules/spectral-mcp-trust-ipv6-broad.yaml",
            "ikanos-mcp-trust-network-too-broad", "'::/0'");
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireOnIpv4MappedWholeIpv4Space() {
        assertWarnsOn("rules/spectral-mcp-trust-ipv4-mapped-broad.yaml",
            "ikanos-mcp-trust-network-too-broad", "'::ffff:0.0.0.0/96'");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireOnIpv6LoopbackWhenAdapterListensOnAllIpv6() {
        assertWarnsOn("rules/spectral-mcp-trust-ipv6-loopback-public.yaml",
            "ikanos-mcp-trust-loopback-on-public-address", "'::1/128'");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireOnLoopbackRangeWithout127001() {
        assertWarnsOn("rules/spectral-mcp-trust-loopback-range-public.yaml",
            "ikanos-mcp-trust-loopback-on-public-address", "'127.1.0.0/16'");
    }

    // ────────────────────────────────────────────────────────────────
    // MCP Apps rules (SEP-1865)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpUiResourceMimeTypeRuleShouldFireWhenViewLacksMcpAppProfile() {
        assertRuleFires("rules/spectral-mcp-ui-violations.yaml",
            "ikanos-mcp-ui-resource-mime-type");
    }

    @Test
    public void mcpUiToolOutputParametersRuleShouldFireWhenUiToolHasNoOutputParameters() {
        ProcessResult result = lintFixtureAtSeverity("rules/spectral-mcp-ui-violations.yaml", "info");
        assertTrue(result.output().contains("ikanos-mcp-ui-tool-output-parameters"),
            "Expected lint output to reference ikanos-mcp-ui-tool-output-parameters.\n"
                + result.output());
    }

    @Test
    public void mcpUiConnectDomainsRuleShouldFireWhenViewDeclaresConnectDomains() {
        ProcessResult result = lintFixtureAtSeverity("rules/spectral-mcp-ui-violations.yaml", "info");
        assertTrue(result.output().contains("ikanos-mcp-ui-connect-domains-empty"),
            "Expected lint output to reference ikanos-mcp-ui-connect-domains-empty.\n"
                + result.output());
    }

    @Test
    public void mcpUiRulesShouldNotFireForCompliantView() {
        ProcessResult result = lintFixtureAtSeverity("rules/spectral-mcp-ui-compliant.yaml", "info");
        assertTrue(!result.output().contains("ikanos-mcp-ui-"),
            "Expected no MCP Apps rule findings for a compliant document.\n" + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // Consumed MCP rules (consumes type: mcp)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpConsumesEndpointHttpsRuleShouldFireForRemoteHttpAndUserinfoBypass() {
        ProcessResult result = lintFixture("rules/spectral-mcp-consumes-violations.yaml");
        assertTrue(result.exitCode() != 0,
            "Expected consumed MCP violations to fail linting.\n" + result.output());
        // One finding per offending endpoint: http://example.com and the two userinfo URLs
        // whose real host is evil.com.
        long findings = result.output().lines()
            .filter(line -> line.contains("ikanos-mcp-consumes-endpoint-https"))
            .count();
        assertEquals(3, findings,
            "Expected ikanos-mcp-consumes-endpoint-https on all three endpoints.\n"
                + result.output());
    }

    @Test
    public void mcpConsumedToolHintsRuleShouldFireWhenConsumedToolHasNoHints() {
        ProcessResult result = lintFixtureAtSeverity("rules/spectral-mcp-consumes-violations.yaml",
            "warn");
        long findings = result.output().lines()
            .filter(line -> line.contains("ikanos-mcp-consumed-tool-hints"))
            .count();
        assertEquals(1, findings,
            "Expected ikanos-mcp-consumed-tool-hints on the one tool without hints.\n"
                + result.output());
    }

    @Test
    public void mcpConsumesRulesShouldNotFireForHttpsOrLoopbackWithHints() {
        ProcessResult result = lintFixtureAtSeverity("rules/spectral-mcp-consumes-compliant.yaml",
            "info");
        assertTrue(!result.output().contains("ikanos-mcp-consumes-endpoint-https"),
            "Expected no endpoint findings for HTTPS and loopback endpoints.\n"
                + result.output());
        assertTrue(!result.output().contains("ikanos-mcp-consumed-tool-hints"),
            "Expected no hints findings when every consumed tool declares hints.\n"
                + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // Flow composition rules (#794)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void namespacesUniqueRuleShouldFireWhenAggregateReusesConsumedNamespace() {
        assertRuleFires("rules/spectral-flow-calls-violations.yaml", "ikanos-namespaces-unique");
    }

    @Test
    public void namespacesUniqueRuleShouldFireWhenImportedAliasReusesAggregateNamespace() {
        assertRuleFires("rules/spectral-namespace-alias-collision.yaml",
            "ikanos-namespaces-unique");
    }

    @Test
    public void flowCallTargetExistsRuleShouldFireWhenCalledFlowIsMissing() {
        assertRuleFires("rules/spectral-flow-calls-violations.yaml",
            "ikanos-flow-call-target-exists");
    }

    @Test
    public void flowCallNoCycleRuleShouldFireWhenFlowsCallEachOther() {
        assertRuleFires("rules/spectral-flow-calls-violations.yaml", "ikanos-flow-call-no-cycle");
    }

    @Test
    public void exposesCallNotFlowRuleShouldFireWhenToolCallsAFlow() {
        assertRuleFires("rules/spectral-flow-calls-violations.yaml",
            "ikanos-exposes-call-not-flow");
    }

    @Test
    public void flowCallSemanticsRuleShouldFireWhenSafeFlowCallsUnsafeOperation() {
        assertRuleFires("rules/spectral-flow-calls-violations.yaml",
            "ikanos-flow-call-semantics-conservative");
    }

    @Test
    public void flowCallRulesShouldNotFireForCompliantComposition() {
        ProcessResult result = lintFixture("rules/spectral-flow-calls-compliant.yaml");
        String output = result.output();
        assertTrue(!output.contains("ikanos-flow-call-")
                && !output.contains("ikanos-exposes-call-not-flow")
                && !output.contains("ikanos-namespaces-unique"),
            "Expected no flow composition findings for a compliant document.\n" + output);
    }

    /**
     * Lint a fixture file under {@code src/test/resources/} reporting findings down to
     * {@code severity}.
     */
    private ProcessResult lintFixtureAtSeverity(String relativePath, String severity) {
        return runCommand(
            "npx",
            "@stoplight/spectral-cli",
            "lint",
            "src/test/resources/" + relativePath,
            "--ruleset",
            rulesetPath.toAbsolutePath().toString(),
            "--fail-severity",
            severity);
    }

    /**
     * Assert that linting a fixture reports {@code ruleId} on the trusted network {@code entry}
     * (quoted as it appears in the rule message).
     */
    private void assertWarnsOn(String fixturePath, String ruleId, String entry) {
        ProcessResult result = lintFixture(fixturePath);
        assertTrue(result.output().lines()
                .anyMatch(line -> line.contains(ruleId) && line.contains(entry)),
            "Expected " + ruleId + " on " + entry + " in '" + fixturePath + "'.\n"
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
