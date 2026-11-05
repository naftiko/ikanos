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
package io.ikanos.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.polychro.core.Linter;
import io.polychro.core.LinterConfig;
import io.polychro.spi.Diagnostic;
import io.polychro.spi.Document;
import io.polychro.spi.Severity;

/**
 * Tests of the Polychro ruleset ({@code ikanos-rules.yml}) and its JavaScript functions, run in
 * process with the Polychro library: no Node.js, no subprocess.
 *
 * <p>The ruleset and its fixtures live in {@code ikanos-spec}; this module reads them by path.
 * It exists because Polychro and {@code ikanos-spec} need incompatible versions of
 * {@code json-schema-validator}, which cannot share one test class path.</p>
 */
public class IkanosPolychroRulesetTest {

    private static Linter linter;

    private Path projectRoot;

    @BeforeAll
    public static void buildLinter() {
        Path ruleset = specModule().resolve("src/main/resources/rules/ikanos-rules.yml");
        LinterConfig config = new LinterConfig(List.of("ruleset"),
            Map.of("ruleset", Map.of("rulesetPath", ruleset.toString())), false, "json-schema");
        linter = Linter.builder().config(config).build();
    }

    @BeforeEach
    public void setUp() {
        projectRoot = specModule();
    }

    /** The {@code ikanos-spec} module, which holds the ruleset and the fixtures. */
    private static Path specModule() {
        return Path.of(System.getProperty("user.dir")).resolve("../ikanos-spec")
            .toAbsolutePath().normalize();
    }

    @Test
    public void testRulesetAcceptsValidExample() {
        LintResult result = lint("src/main/resources/schemas/examples/reverse-tunnel-ziti.yml");

        assertEquals(0, result.exitCode(),
            "Expected valid example to pass Polychro linting.\n" + result.output());
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

            LintResult result = lint(tmpFile.toAbsolutePath().toString());

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
        LintResult result = lint("src/test/resources/rules/polychro-semantics-inconsistent.yaml", "warn");

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        assertTrue(output.contains("ikanos-aggregate-semantics-consistency"),
            "Expected lint output to reference ikanos-aggregate-semantics-consistency.\n"
                + output);
        // The rule fired on both contradicting hints (readOnly and destructive): check the
        // paths of the findings.
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
        LintResult result = lint("src/test/resources/rules/polychro-semantics-inconsistent.yaml", "warn");

        assertTrue(result.exitCode() != 0,
            "Expected inconsistent semantics document to produce warnings.\n"
                + result.output());
        String output = result.output();
        // The REST contradiction reports against the resource operation; assert the
        // rule fires on a REST exposes adapter path.
        assertTrue(output.contains("exposes") && output.contains("operations"),
            "Expected warning targeting a REST exposes operation contradicting "
                + "safe semantics.\n" + output);
    }

    @Test
    public void semanticsConsistencyRuleShouldNotWarnWhenHintsAreConsistent()
            throws IOException {
        LintResult result = lint("src/test/resources/rules/polychro-semantics-consistent.yaml");

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
    // import-related Polychro rule. Each assertion verifies:
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
        LintResult result = lintFixture("imports/import-from-self.yaml");
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
        LintResult result = lintFixture("imports/exposes-control-without-namespace.yaml");
        assertEquals(0, result.exitCode(),
            "Expected the fixture to lint cleanly.\n" + result.output());
        assertFalse(result.output().contains("ikanos-exposes-namespace-required"),
            "Expected no ikanos-exposes-namespace-required error for a standalone control "
                + "adapter.\n" + result.output());
    }

    @Test
    public void exposesNamespaceRequiredRuleShouldNotFireForCapabilityControlExpose() {
        LintResult result = lintFixture(
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
        LintResult result = lint("src/test/resources/rules/polychro-tunnel-identity-unbound.yaml");

        assertTrue(result.exitCode() != 0,
            "Expected unbound-identity fixture to fail linting.\n" + result.output());
        assertTrue(result.output().contains("ikanos-tunnel-identity-must-bind"),
            "Expected lint output to reference ikanos-tunnel-identity-must-bind.\n"
                + result.output());
    }

    @Test
    public void tunnelIdentityMustBindRuleShouldNotFireWhenBindingIsDeclared() {
        LintResult result = lint("src/test/resources/rules/polychro-tunnel-identity-bound.yaml");

        String output = result.output();
        assertTrue(!output.contains("ikanos-tunnel-identity-must-bind"),
            "Expected no tunnel-identity-must-bind warnings for a correctly bound "
                + "document.\n" + output);
    }

    @Test
    public void tunnelFallbackDirectWarnsRuleShouldFireOnNonLoopbackBaseUri() {
        LintResult result = lint("src/test/resources/rules/polychro-tunnel-fallback-direct-public.yaml");

        assertTrue(result.output().contains("ikanos-tunnel-fallback-direct-warns"),
            "Expected lint output to reference ikanos-tunnel-fallback-direct-warns.\n"
                + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // MCP trust rules (#483)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireWhenEveryAddressIsTrusted() {
        LintResult result = lintFixture("rules/polychro-mcp-trust-broad-network.yaml");
        assertTrue(result.output().contains("ikanos-mcp-trust-network-too-broad"),
            "Expected 0.0.0.0/0 and ::/0 in trustedNetworks to be flagged (#483).\n"
                + result.output());
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldNotFireOnScopedNetworks() {
        LintResult result = lintFixture("rules/polychro-mcp-trust-networks-scoped.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-network-too-broad"),
            "Expected scoped private networks not to be flagged.\n" + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireWhenAdapterListensBeyondLoopback() {
        LintResult result =
            lintFixture("rules/polychro-mcp-trust-loopback-public-address.yaml");
        assertTrue(result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected trusted loopback peers on a 0.0.0.0 adapter to be flagged (#483).\n"
                + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldNotFireWhenAdapterListensOnLoopback() {
        LintResult result =
            lintFixture("rules/polychro-mcp-trust-loopback-local-address.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected trusted loopback peers on a loopback adapter not to be flagged.\n"
                + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireWhenAddressIsAHostnameStartingWith127() {
        assertWarnsOn("rules/polychro-mcp-trust-loopback-hostname-127-prefix.yaml",
            "ikanos-mcp-trust-loopback-on-public-address", "'127.0.0.1/32'");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldNotFireWhenAdapterListensOnExpandedIpv6Loopback() {
        LintResult result = lintFixture("rules/polychro-mcp-trust-loopback-expanded-ipv6.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected 0:0:0:0:0:0:0:1 to be read as loopback.\n" + result.output());
    }

    @Test
    public void mcpTrustLoopbackRuleShouldNotFireOnScopedNetworks() {
        LintResult result = lintFixture("rules/polychro-mcp-trust-networks-scoped.yaml");
        assertTrue(!result.output().contains("ikanos-mcp-trust-loopback-on-public-address"),
            "Expected scoped private networks not to be flagged.\n" + result.output());
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireOnTheIpv6WholeAddressSpace() {
        assertWarnsOn("rules/polychro-mcp-trust-ipv6-broad.yaml",
            "ikanos-mcp-trust-network-too-broad", "'::/0'");
    }

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldFireOnIpv4MappedWholeIpv4Space() {
        assertWarnsOn("rules/polychro-mcp-trust-ipv4-mapped-broad.yaml",
            "ikanos-mcp-trust-network-too-broad", "'::ffff:0.0.0.0/96'");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireOnIpv6LoopbackWhenAdapterListensOnAllIpv6() {
        assertWarnsOn("rules/polychro-mcp-trust-ipv6-loopback-public.yaml",
            "ikanos-mcp-trust-loopback-on-public-address", "'::1/128'");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldFireOnLoopbackRangeWithout127001() {
        assertWarnsOn("rules/polychro-mcp-trust-loopback-range-public.yaml",
            "ikanos-mcp-trust-loopback-on-public-address", "'127.1.0.0/16'");
    }

    // ────────────────────────────────────────────────────────────────
    // Shared listener rules (#893)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void sharedListenerRulesShouldNotFireOnDistinctMountsAndBoundHosts() {
        LintResult result = lintFixture("rules/polychro-shared-listener-compliant.yaml");
        assertFalse(result.output().contains("ikanos-exposes-mounts-distinct"),
            "Expected distinct paths and hosts on one port not to be flagged.\n"
                + result.output());
        assertFalse(result.output().contains("ikanos-exposes-hosts-must-bind"),
            "Expected a hosts entry bound to a declared key not to be flagged.\n"
                + result.output());
    }

    @Test
    public void mountsDistinctRuleShouldFireWhenTwoAdaptersShareTheRootOfAPort() {
        assertRuleFires("rules/polychro-shared-listener-same-path.yaml",
            "ikanos-exposes-mounts-distinct");
    }

    @Test
    public void mountsDistinctRuleShouldFireWhenAPathIsNestedInAnother() {
        assertRuleFires("rules/polychro-shared-listener-nested-path.yaml",
            "ikanos-exposes-mounts-distinct");
    }

    @Test
    public void hostsMustBindRuleShouldFireWhenTheKeyIsNotDeclared() {
        assertRuleFires("rules/polychro-shared-listener-unbound-host.yaml",
            "ikanos-exposes-hosts-must-bind");
    }

    // ────────────────────────────────────────────────────────────────
    // MCP Apps rules (SEP-1865)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpUiResourceMimeTypeRuleShouldFireWhenViewLacksMcpAppProfile() {
        assertRuleFires("rules/polychro-mcp-ui-violations.yaml",
            "ikanos-mcp-ui-resource-mime-type");
    }

    @Test
    public void mcpUiToolOutputParametersRuleShouldFireWhenUiToolHasNoOutputParameters() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-ui-violations.yaml", "info");
        assertTrue(result.output().contains("ikanos-mcp-ui-tool-output-parameters"),
            "Expected lint output to reference ikanos-mcp-ui-tool-output-parameters.\n"
                + result.output());
    }

    @Test
    public void mcpUiConnectDomainsRuleShouldFireWhenViewDeclaresConnectDomains() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-ui-violations.yaml", "info");
        assertTrue(result.output().contains("ikanos-mcp-ui-connect-domains-empty"),
            "Expected lint output to reference ikanos-mcp-ui-connect-domains-empty.\n"
                + result.output());
    }

    @Test
    public void mcpUiRulesShouldNotFireForCompliantView() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-ui-compliant.yaml", "info");
        assertTrue(!result.output().contains("ikanos-mcp-ui-"),
            "Expected no MCP Apps rule findings for a compliant document.\n" + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // Agent Skills over MCP rules (SEP-2640)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpSkillAllowedToolsRuleShouldFireWhenSkillDeclaresAllowedTools() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-skills-violations.yaml",
            "info");
        assertTrue(result.output().contains("ikanos-mcp-skill-allowed-tools"),
            "Expected lint output to reference ikanos-mcp-skill-allowed-tools.\n"
                + result.output());
    }

    @Test
    public void mcpSkillRulesShouldNotFireForCompliantSkill() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-skills-compliant.yaml",
            "info");
        assertTrue(!result.output().contains("ikanos-mcp-skill-"),
            "Expected no Agent Skills rule findings for a compliant document.\n"
                + result.output());
    }

    @Test
    public void skillAdapterRemovedRuleShouldFireForCapabilityExpose() {
        assertRuleFires("rules/polychro-skill-adapter-removed-violations.yaml",
            "ikanos-skill-adapter-removed");
    }

    @Test
    public void skillAdapterRemovedRuleShouldFireForStandaloneExpose() {
        assertRuleFires("rules/polychro-skill-adapter-removed-standalone-violations.yaml",
            "ikanos-skill-adapter-removed");
    }

    @Test
    public void skillAdapterRemovedRuleShouldNotFireForSkillsOnMcpAdapter() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-skills-compliant.yaml",
            "info");
        assertTrue(!result.output().contains("ikanos-skill-adapter-removed"),
            "Expected no removed-adapter finding for skills on an MCP adapter.\n"
                + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // Consumed MCP rules (consumes type: mcp)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpConsumesEndpointHttpsRuleShouldFireForRemoteHttpAndUserinfoBypass() {
        LintResult result = lintFixture("rules/polychro-mcp-consumes-violations.yaml");
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
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-consumes-violations.yaml",
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
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-consumes-compliant.yaml",
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
        assertRuleFires("rules/polychro-flow-calls-violations.yaml", "ikanos-namespaces-unique");
    }

    @Test
    public void namespacesUniqueRuleShouldFireWhenImportedAliasReusesAggregateNamespace() {
        assertRuleFires("rules/polychro-namespace-alias-collision.yaml",
            "ikanos-namespaces-unique");
    }

    @Test
    public void flowCallTargetExistsRuleShouldFireWhenCalledFlowIsMissing() {
        assertRuleFires("rules/polychro-flow-calls-violations.yaml",
            "ikanos-flow-call-target-exists");
    }

    @Test
    public void flowCallNoCycleRuleShouldFireWhenFlowsCallEachOther() {
        assertRuleFires("rules/polychro-flow-calls-violations.yaml", "ikanos-flow-call-no-cycle");
    }

    @Test
    public void exposesCallNotFlowRuleShouldFireWhenToolCallsAFlow() {
        assertRuleFires("rules/polychro-flow-calls-violations.yaml",
            "ikanos-exposes-call-not-flow");
    }

    @Test
    public void flowCallSemanticsRuleShouldFireWhenSafeFlowCallsUnsafeOperation() {
        assertRuleFires("rules/polychro-flow-calls-violations.yaml",
            "ikanos-flow-call-semantics-conservative");
    }

    @Test
    public void flowCallRulesShouldNotFireForCompliantComposition() {
        LintResult result = lintFixture("rules/polychro-flow-calls-compliant.yaml");
        String output = result.output();
        assertTrue(!output.contains("ikanos-flow-call-")
                && !output.contains("ikanos-exposes-call-not-flow")
                && !output.contains("ikanos-namespaces-unique"),
            "Expected no flow composition findings for a compliant document.\n" + output);
    }

    @Test
    public void taskModeRuleShouldFireWhenTheCapabilityHasNoDurabilityMode() {
        assertRuleFires("rules/polychro-durability-violations.yaml",
            "ikanos-task-mode-requires-durability");
    }

    @Test
    public void elicitSafetyRuleShouldFireWhenAQuestionFollowsAWriteUnderNone() {
        assertRuleFires("rules/polychro-durability-violations.yaml",
            "ikanos-elicit-after-unsafe-requires-durability");
    }

    @Test
    public void durabilityRulesShouldNotFireOnTheLocalMode() {
        LintResult result = lintFixture("rules/polychro-durability-local.yaml");
        assertTrue(result.exitCode() == 0 && !result.output().contains("ikanos-durability-")
                && !result.output().contains("ikanos-task-mode-"),
            "Expected the local mode to lint clean.\n" + result.output());
    }

    @Test
    public void durabilityRulesShouldNotFireOnTheMemoryMode() {
        String output = lintFixture("rules/polychro-durability-compliant.yaml").output();
        assertTrue(!output.contains("ikanos-task-mode-")
                && !output.contains("ikanos-elicit-")
                && !output.contains("ikanos-durability-")
                && !output.contains("ikanos-sealing-key-"),
            "Expected no durability findings on the memory mode.\n" + output);
    }

    @Test
    public void sealingKeyRuleShouldWarnOnAKeyWrittenIntoTheCapability() {
        assertWarnsOn("rules/polychro-durability-warnings.yaml",
            "ikanos-sealing-key-must-bind", "written into the capability");
    }

    @Test
    public void sealingKeyRuleShouldWarnOnAKeyNoBindingDeclares() {
        assertWarnsOn("rules/polychro-durability-sealing-key-unbound.yaml",
            "ikanos-sealing-key-must-bind", "'IKANOS_SEALING_KEY'");
    }

    @Test
    public void elicitOverRestRuleShouldWarnOnEveryOperationThatReachesAQuestion() {
        String fixture = "rules/polychro-durability-warnings.yaml";
        assertWarnsOn(fixture, "ikanos-elicit-not-served-over-rest", "'confirm-inline'");
        assertWarnsOn(fixture, "ikanos-elicit-not-served-over-rest", "'confirm-by-ref'");
        assertWarnsOn(fixture, "ikanos-elicit-not-served-over-rest", "'clear-by-ref'");
        String output = lintFixture(fixture).output();
        assertTrue(output.lines().noneMatch(line -> line.contains("ikanos-elicit-not-served")
                && line.contains("'get-voyage'")),
            "Expected no elicit warning on an operation that asks nothing.\n" + output);
    }

    @Test
    public void elicitSafetyRuleShouldNotFireWhenTheQuestionComesBeforeAnyWrite() {
        String output = lintFixture("rules/polychro-durability-none-compliant.yaml").output();
        assertTrue(!output.contains("ikanos-elicit-"),
            "Expected no elicit finding when only reads precede the question.\n" + output);
    }

    /**
     * Lint a fixture file under {@code src/test/resources/}, failing on findings at
     * {@code severity} or above.
     */
    private LintResult lintFixtureAtSeverity(String relativePath, String severity) {
        return lint("src/test/resources/" + relativePath, severity);
    }

    /**
     * Assert that linting a fixture reports {@code ruleId} on the trusted network {@code entry}
     * (quoted as it appears in the rule message).
     */
    private void assertWarnsOn(String fixturePath, String ruleId, String entry) {
        LintResult result = lintFixture(fixturePath);
        assertTrue(result.output().lines()
                .anyMatch(line -> line.contains(ruleId) && line.contains(entry)),
            "Expected " + ruleId + " on " + entry + " in '" + fixturePath + "'.\n"
                + result.output());
    }

    /**
     * Lint a fixture file under {@code src/test/resources/} with the project ruleset.
     */
    private LintResult lintFixture(String relativePath) {
        return lint("src/test/resources/" + relativePath);
    }

    /**
     * Assert that linting a fixture both exits non-zero and includes the given rule id
     * in its output. Use this for any rule whose severity is {@code error}.
     */
    private void assertRuleFires(String fixturePath, String ruleId) {
        LintResult result = lintFixture(fixturePath);
        assertTrue(result.exitCode() != 0,
            "Expected fixture '" + fixturePath + "' to fail linting, but it passed.\n"
                + result.output());
        assertTrue(result.output().contains(ruleId),
            "Expected lint output for '" + fixturePath + "' to reference " + ruleId + ".\n"
                + result.output());
    }

    /** Lint a YAML file with the project ruleset, failing on errors. */
    private LintResult lint(String file) {
        return lint(file, "error");
    }

    /**
     * Lint a YAML file with the project ruleset. The result fails ({@code exitCode} 1) when a
     * finding is at {@code failSeverity} ({@code error}, {@code warn} or {@code info}) or above.
     * Its output has one line per finding: path, severity, rule and message.
     */
    private LintResult lint(String file, String failSeverity) {
        List<Diagnostic> diagnostics = linter.lint(Document.fromYaml(projectRoot.resolve(file)));
        Severity threshold = Severity.valueOf(failSeverity.toUpperCase(java.util.Locale.ROOT));
        StringBuilder output = new StringBuilder();
        boolean failed = false;
        for (Diagnostic diagnostic : diagnostics) {
            output.append(diagnostic.path()).append("  ")
                .append(diagnostic.severity().name().toLowerCase(java.util.Locale.ROOT))
                .append("  ").append(diagnostic.code()).append("  ")
                .append(diagnostic.message()).append('\n');
            failed |= diagnostic.severity().ordinal() <= threshold.ordinal();
        }
        return new LintResult(failed ? 1 : 0, output.toString());
    }

    private record LintResult(int exitCode, String output) {
    }
}
