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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

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
 * process with the Polychro library, the engine the {@code lint-capabilities} job runs: no
 * Node.js, no subprocess.
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
        LintResult result =
            lintFixtureAtSeverity("rules/polychro-semantics-inconsistent.yaml", "warn");

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
        LintResult result =
            lintFixtureAtSeverity("rules/polychro-semantics-inconsistent.yaml", "warn");

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
    public void capabilityNoRootSectionsRuleShouldFireWhenCapabilityDocumentHasRootBinds() {
        assertRuleFires("rules/capability-root-binds.yaml", "ikanos-capability-no-root-sections");
    }

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
    public void aggregatesNamespaceRequiredRuleShouldNotFireForCapabilityAggregateImport() {
        LintResult result = lintFixture("imports/capability-aggregates-import-entry.yaml");
        assertFalse(result.output().contains("ikanos-aggregates-namespace-required"),
            "Expected no namespace error on an aggregate import entry.\n" + result.output());
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
        LintResult result = lintFixture("rules/polychro-tunnel-fallback-direct-public.yaml");

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

    @Test
    public void mcpTrustNetworkTooBroadRuleShouldReportEachBroadEntryAtItsPath() {
        // #1069: the function read Polychro's missing context and failed on every adapter.
        assertReportsAt("rules/polychro-mcp-trust-broad-network.yaml",
            "ikanos-mcp-trust-network-too-broad",
            "$.capability.exposes[0].trust.trustedNetworks[0]");
        assertReportsAt("rules/polychro-mcp-trust-broad-network.yaml",
            "ikanos-mcp-trust-network-too-broad",
            "$.capability.exposes[0].trust.trustedNetworks[1]");
    }

    @Test
    public void mcpTrustLoopbackRuleShouldReportTheLoopbackEntryAtItsPath() {
        assertReportsAt("rules/polychro-mcp-trust-loopback-public-address.yaml",
            "ikanos-mcp-trust-loopback-on-public-address",
            "$.capability.exposes[0].trust.trustedNetworks[0]");
    }

    @Test
    public void mcpTrustRulesShouldNotFailOnScopedNetworks() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-mcp-trust-networks-scoped.yaml",
            "info");
        assertEquals(0, result.exitCode(),
            "#1069: a compliant trust block must lint clean, without a function failure.\n"
                + result.output());
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
    // Authorization (#999)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void authorizationRulesShouldNotFireOnAuthorizationOfOAuth2Adapters() {
        LintResult result = lintFixture("rules/polychro-authorization-compliant.yaml");
        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("ikanos-authorization-"), result.output());
        assertFalse(result.output().contains("ikanos-oauth2-scopes-not-enforced"),
            result.output());
    }

    @Test
    public void requiresOAuth2RuleShouldFireOnABearerAdapterAndOnStdio() {
        LintResult result = lintFixture("rules/polychro-authorization-violations.yaml");
        assertTrue(result.output().contains("operation 'records.update-record' needs"),
            result.output());
        assertTrue(result.output().contains("cannot be enforced on the stdio transport"),
            result.output());
    }

    @Test
    public void requiresScopesDeclaredRuleShouldFireOnAnUnlistedScope() {
        assertRuleFires("rules/polychro-authorization-violations.yaml",
            "ikanos-authorization-scopes-declared");
    }

    @Test
    public void oauth2ScopesNotEnforcedRuleShouldWarnWhenNoAuthorizationIsDeclared() {
        LintResult result = lintFixture("rules/polychro-authorization-violations.yaml");
        assertTrue(result.output().contains("ikanos-oauth2-scopes-not-enforced"),
            result.output());
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
    public void mcpUiToolOutputParametersRuleShouldReportTheUiTool() {
        // #1069: a [?(...)] filter on the tools map selected nothing under Polychro.
        assertReportsAt("rules/polychro-mcp-ui-violations.yaml",
            "ikanos-mcp-ui-tool-output-parameters",
            "$.capability.exposes[0].tools.get-forecast");
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
    // MCP tool input examples
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpToolInputExamplesRuleShouldFireForEachInvalidExample() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-mcp-tool-input-examples-violations.yaml", "info");
        long findings = result.output().lines()
            .filter(line -> line.contains("ikanos-mcp-tool-input-examples")).count();
        assertEquals(9, findings,
            "Expected one finding per invalid example (enum, number, hexadecimal number,"
                + " spaced integer, large unquoted integer, decimal integer enum value,"
                + " number on a string input, boolean, object).\n"
                + result.output());
    }

    @Test
    public void mcpToolInputExamplesRuleShouldNotFireForValidExamples() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-mcp-tool-input-examples-compliant.yaml", "info");
        assertTrue(!result.output().contains("ikanos-mcp-tool-input-examples"),
            "Expected no example findings for valid examples.\n" + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // MCP tool search rules
    // ────────────────────────────────────────────────────────────────

    @Test
    public void mcpToolSearchReservedNamesRuleShouldFireWhenADeclaredMetaToolNameIsATool() {
        assertRuleFires("rules/polychro-mcp-tool-search-violations.yaml",
            "ikanos-mcp-tool-search-reserved-names");
    }

    @Test
    public void mcpToolSearchImplicitReservedNamesRuleShouldFireWhenADefaultMetaToolNameIsATool() {
        assertToolSearchRuleFires("ikanos-mcp-tool-search-implicit-reserved-names");
    }

    @Test
    public void mcpToolSearchLookalikeNamesRuleShouldFireForSearchUnderscoreTools() {
        assertToolSearchRuleFires("ikanos-mcp-tool-search-lookalike-names");
    }

    @Test
    public void mcpToolSearchAlwaysListedExistsRuleShouldFireForAnUnknownTool() {
        assertRuleFires("rules/polychro-mcp-tool-search-violations.yaml",
            "ikanos-mcp-tool-search-always-listed-exists");
    }

    @Test
    public void mcpToolSearchAlwaysListedSizeRuleShouldFireAboveFiveTools() {
        assertToolSearchRuleFires("ikanos-mcp-tool-search-always-listed-size");
    }

    @Test
    public void mcpToolSearchDescriptionRuleShouldFireForDeferredModeWithoutDescription() {
        assertToolSearchRuleFires("ikanos-mcp-tool-search-description");
    }

    @Test
    public void mcpToolSearchSmallCatalogRuleShouldFireForDeferredModeOnFewTools() {
        assertToolSearchRuleFires("ikanos-mcp-tool-search-small-catalog");
    }

    @Test
    public void mcpToolSearchRulesShouldNotFireForACompliantEndpoint() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-mcp-tool-search-compliant.yaml", "info");
        assertTrue(!result.output().contains("ikanos-mcp-tool-search-"),
            "Expected no tool search findings for a compliant document.\n" + result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // MCP tool description rules (#1121)
    // ────────────────────────────────────────────────────────────────

    private static final String TOOL_DESCRIPTION_VIOLATIONS =
        "rules/polychro-mcp-tool-description-violations.yaml";

    private static final String SHARED_TOOL_DESCRIPTIONS =
        "rules/polychro-mcp-tool-description-shared.yaml";

    @Test
    public void mcpToolDescriptionRuleShouldFireForABlankDescriptionOnAListedAdapter() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description",
            "$.capability.exposes[0].tools.blank-tool");
    }

    @Test
    public void mcpToolDescriptionRuleShouldFireForARefToolWhoseFlowHasNoDescription() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description",
            "$.capability.exposes[0].tools.undescribed-flow-tool");
    }

    @Test
    public void mcpToolDescriptionRuleShouldFireForAWhitespaceOnlyDescriptionOnARefTool() {
        // The engine inherits the flow's description only when the tool's is null or empty.
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description",
            "$.capability.exposes[0].tools.whitespace-ref");
    }

    @Test
    public void mcpToolDescriptionDeferredRuleShouldFireForABlankDescriptionInDeferredMode() {
        assertRuleFires(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-deferred");
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-deferred",
            "$.capability.exposes[1].tools.blank-deferred-tool");
    }

    @Test
    public void mcpToolDescriptionRuleShouldTreatAPinnedToolOfADeferredAdapterAsListed() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description",
            "$.capability.exposes[1].tools.pinned-tool");
    }

    @Test
    public void mcpToolDescriptionRulesShouldReportEachBlankToolUnderOneRuleOnly() {
        String output = lintFixture(TOOL_DESCRIPTION_VIOLATIONS).output();
        assertFalse(output.lines().anyMatch(line -> line.contains("blank-deferred-tool")
                && line.contains("  ikanos-mcp-tool-description  ")),
            "A deferred tool must be reported by the deferred rule only.\n" + output);
        assertFalse(output.lines().anyMatch(line -> (line.contains(".tools.blank-tool ")
                    || line.contains(".tools.pinned-tool "))
                && line.contains("ikanos-mcp-tool-description-deferred")),
            "A listed or pinned tool must be reported by the listed rule only.\n" + output);
    }

    @Test
    public void mcpToolDescriptionRestatesNameRuleShouldFireForSnakeAndCamelCaseNames() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-restates-name",
            "$.capability.exposes[0].tools.sync_application.description");
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-restates-name",
            "$.capability.exposes[0].tools.getForecast.description");
    }

    @Test
    public void mcpToolDescriptionRestatesNameRuleShouldIgnoreInflectedFillerWords() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-restates-name",
            "$.capability.exposes[0].tools.list-users.description");
    }

    @Test
    public void mcpToolDescriptionSimilarRuleShouldFireOnTheSecondToolOfAPair() {
        assertReportsAt(TOOL_DESCRIPTION_VIOLATIONS, "ikanos-mcp-tool-description-similar",
            "$.capability.exposes[0].tools.list-closed-incidents.description");
        String output = lintFixture(TOOL_DESCRIPTION_VIOLATIONS).output();
        assertEquals(1, output.lines()
                .filter(line -> line.contains("ikanos-mcp-tool-description-similar")).count(),
            "Expected one finding per similar pair.\n" + output);
    }

    @Test
    public void mcpToolDescriptionRulesShouldNotFireForACompliantDocument() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-mcp-tool-description-compliant.yaml", "info");
        assertFalse(result.output().contains("ikanos-mcp-tool-description"),
            "Expected no tool description findings for a compliant document.\n"
                + result.output());
    }

    @Test
    public void mcpToolDescriptionRuleShouldResolveRefInRootLevelSectionsWithArrayFlows() {
        assertReportsAt(SHARED_TOOL_DESCRIPTIONS, "ikanos-mcp-tool-description",
            "$.exposes[0].tools.undescribed-ref");
    }

    @Test
    public void mcpToolDescriptionRestatesNameRuleShouldFireOnAnInheritedDescription() {
        // tools/list sends the flow's description under the tool's name.
        assertReportsAt(SHARED_TOOL_DESCRIPTIONS, "ikanos-mcp-tool-description-restates-name",
            "$.exposes[0].tools.get-forecast");
    }

    private void assertToolSearchRuleFires(String ruleId) {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-mcp-tool-search-violations.yaml", "info");
        assertTrue(result.output().contains(ruleId),
            "Expected lint output to reference " + ruleId + ".\n" + result.output());
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
    public void rulesetShouldAcceptSnakeCaseMcpToolNames() {
        LintResult result = lintFixture("rules/polychro-mcp-tool-names-compliant.yaml");
        assertEquals(0, result.exitCode(),
            "#1006: snake_case MCP tool names, and a skill naming them, must lint clean.\n"
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
    // Cache hint rules (#1022)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void cachePublicCallerDependentRuleShouldWarnOnEachCallerDependentPublicScope() {
        assertEquals(8, countFindings("rules/polychro-cache-hints-violations.yaml",
            "ikanos-cache-public-caller-dependent"));
    }

    @Test
    public void cacheTtlFromUpstreamRuleShouldWarnWhereItHasNoEffect() {
        assertEquals(5, countFindings("rules/polychro-cache-hints-violations.yaml",
            "ikanos-cache-ttl-from-upstream-no-effect"));
    }

    @Test
    public void cacheHintRulesShouldNotFireWhenScopesAreAutoOrPrivate() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-cache-hints-compliant.yaml",
            "info");
        assertEquals(0, result.exitCode(),
            "Expected the compliant cache hints fixture to lint cleanly.\n" + result.output());
        assertFalse(result.output().contains("ikanos-cache-"),
            "Expected no cache hint findings.\n" + result.output());
    }

    private long countFindings(String fixturePath, String ruleId) {
        LintResult result = lintFixtureAtSeverity(fixturePath, "warn");
        return result.output().lines().filter(line -> line.contains(ruleId)).count();
    }

    // Consumed SQL rules (#1051)
    // ────────────────────────────────────────────────────────────────

    private long findings(LintResult result, String ruleId) {
        return result.output().lines().filter(line -> line.contains(ruleId)).count();
    }

    @Test
    public void sqlNoInlineCredentialsRuleShouldFireForLiteralUsernameAndPassword() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(2, findings(result, "ikanos-sql-no-inline-credentials"),
            "Expected ikanos-sql-no-inline-credentials on username and password.\n"
                + result.output());
    }

    @Test
    public void sqlStatementReadOnlyRuleShouldFireForADeleteStatement() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(1, findings(result, "ikanos-sql-statement-read-only"),
            "Expected ikanos-sql-statement-read-only on the DELETE statement.\n"
                + result.output());
    }

    @Test
    public void sqlPlaceholdersBoundRuleShouldFireForUnmatchedPlaceholderAndUnusedInput() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(2, findings(result, "ikanos-sql-placeholders-bound"),
            "Expected ikanos-sql-placeholders-bound on {{cust-id}} and on customer-id.\n"
                + result.output());
    }

    @Test
    public void sqlPlaceholdersBoundRuleShouldFireForQuotedPlaceholderSectionAndJdbcSyntax() {
        LintResult result = lintFixture("rules/polychro-sql-placeholders-violations.yaml");
        assertEquals(4, findings(result, "ikanos-sql-placeholders-bound"),
            "Expected ikanos-sql-placeholders-bound on '%{{text}}%', {{#active}}, {fn ...}"
                + " and ?.\n" + result.output());
    }

    @Test
    public void sqlPlaceholdersBoundRuleShouldReadAMySqlDoubleDashAsTheEngineDoes() {
        LintResult result = lintFixture("rules/polychro-sql-mysql-comments.yaml");
        assertEquals(1, findings(result, "ikanos-sql-placeholders-bound"),
            "Expected ikanos-sql-placeholders-bound on {{status}} only.\n" + result.output());
        assertTrue(result.output().contains("{{status}}"),
            "Expected the finding on {{status}}, after -- and a no-break space.\n"
                + result.output());
        assertTrue(!result.output().contains("{{note}}"),
            "Expected no finding on {{note}}, after -- and a control character.\n"
                + result.output());
    }

    @Test
    public void sqlStoredProcedureReadOnlyRuleShouldFireWithoutReadOnly() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(1, findings(result, "ikanos-sql-stored-procedure-read-only"),
            "Expected ikanos-sql-stored-procedure-read-only on get-balance.\n"
                + result.output());
    }

    @Test
    public void sqlStoredProcedureParametersRuleShouldFireForAGapAndOutParametersOnAStatement() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(2, findings(result, "ikanos-sql-stored-procedure-parameters"),
            "Expected ikanos-sql-stored-procedure-parameters on get-balance and total.\n"
                + result.output());
    }

    @Test
    public void sqlWriteStatementRuleShouldFireForATruncate() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(1, findings(result, "ikanos-sql-write-statement"),
            "Expected ikanos-sql-write-statement on empty-orders." + System.lineSeparator()
                + result.output());
    }

    @Test
    public void sqlWriteExposureRuleShouldFireForAReadOnlyToolAndARestGet() {
        LintResult result = lintFixture("rules/polychro-sql-consumes-violations.yaml");
        assertEquals(2, findings(result, "error  ikanos-sql-write-exposure"),
            "Expected ikanos-sql-write-exposure on the MCP tool and the REST GET."
                + System.lineSeparator() + result.output());
    }

    @Test
    public void sqlWriteExposureRuleShouldFollowStepsRefsResourcesAndFlows() {
        LintResult result = lintFixture("rules/polychro-sql-write-exposure-indirect.yaml");
        assertTrue(result.exitCode() != 0, "a write exposed as safe fails the lint");
        assertEquals(4, findings(result, "error  ikanos-sql-write-exposure"),
            "Expected ikanos-sql-write-exposure on the step, the ref, the resource and the safe"
                + " flow." + System.lineSeparator() + result.output());
    }

    @Test
    public void sqlWriteExposureRuleShouldFireForWriteToolsInheritingReadOnly() {
        LintResult result = lintFixture(
            "rules/polychro-sql-write-exposure-inherited-violations.yaml");
        assertTrue(result.exitCode() != 0, "a write tool inheriting readOnly fails the lint");
        assertEquals(3, findings(result, "error  ikanos-sql-write-exposure"),
            "Expected ikanos-sql-write-exposure on the call, the step and the ref tools."
                + System.lineSeparator() + result.output());
    }

    @Test
    public void sqlWriteExposureRuleShouldNotFireForWriteToolsOverridingInheritedReadOnly() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-sql-write-exposure-inherited-compliant.yaml", "warn");
        assertEquals(0, findings(result, "ikanos-sql-write-exposure"),
            "Expected no ikanos-sql-write-exposure." + System.lineSeparator() + result.output());
    }

    @Test
    public void sqlRulesShouldNotFireForBoundCredentialsAndMatchedReadStatements() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-sql-consumes-compliant.yaml",
            "info");
        assertTrue(!result.output().contains("ikanos-sql-"),
            "Expected no consumed SQL findings.\n" + result.output());
    }

    @Test
    public void sqlNoUrlCredentialsRuleShouldFireForAPasswordInTheUrl() {
        LintResult result = lintFixture("rules/polychro-sql-credentials-violations.yaml");
        assertEquals(2, findings(result, "ikanos-sql-no-url-credentials"),
            "Expected ikanos-sql-no-url-credentials on password= and on user:password@.\n"
                + result.output());
    }

    @Test
    public void sqlNoInlineCredentialsRuleShouldFireForALiteralPasswordProperty() {
        LintResult result = lintFixture("rules/polychro-sql-credentials-violations.yaml");
        assertEquals(1, findings(result, "ikanos-sql-no-inline-credentials"),
            "Expected ikanos-sql-no-inline-credentials on connection.properties.password.\n"
                + result.output());
    }

    @Test
    public void sqlStatementReadOnlyRuleShouldAcceptAParenthesizedSelect() {
        LintResult result = lintFixture("rules/polychro-sql-credentials-violations.yaml");
        assertEquals(0, findings(result, "ikanos-sql-statement-read-only"),
            "Expected no ikanos-sql-statement-read-only on (select ...).\n" + result.output());
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
    public void flowCallSemanticsRuleShouldFireWhenAToolsHintsPromiseMoreThanTheFlowsItsStepsCall() {
        // #828: a call step may reach a flow, but the tool's resolved readOnly / idempotent
        // hints must hold for that flow, as for a flow calling another flow.
        LintResult result = lintFixture("rules/polychro-tool-hints-flow-steps.yaml");
        // Exactly these: quote's flow is safe, pay claims nothing, pay-destructive resolves
        // readOnly to false, and the resource and the REST operation have no hints.
        assertEquals(List.of(
                "$.capability.exposes[0].tools.pay-idempotent.hints.idempotent",
                "$.capability.exposes[0].tools.pay-read-only.hints.readOnly",
                "$.capability.exposes[1].tools.pay-by-default"),
            result.output().lines()
                .filter(line -> line.contains("ikanos-flow-call-semantics-conservative"))
                .map(line -> line.substring(0, line.indexOf("  "))).sorted().toList(),
            result.output());
    }

    @Test
    public void exposesCallNotFlowRuleShouldLetACallStepReachAFlow() {
        // #828: only the unit's own call: is refused; an MCP resource has no ref.
        LintResult result = lintFixture("rules/polychro-tool-hints-flow-steps.yaml");
        assertEquals(0, findings(result, "ikanos-exposes-call-not-flow"), result.output());
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

    // ────────────────────────────────────────────────────────────────
    // Shared definitions (#910)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void definitionsReferenceRuleShouldFireOnUnknownAndWrongKindReferences() {
        LintResult result = lintFixture("rules/polychro-definitions-violations.yaml");
        String output = result.output();
        assertTrue(result.exitCode() != 0, output);
        assertTrue(output.contains("Unknown input parameter set 'missing-in-aggregate'"), output);
        assertTrue(output.contains("Unknown input parameter set 'key'. MCP adapter 'tools'"),
            "A tool must not reach an aggregate's definitions.\n" + output);
        assertTrue(output.contains("'date-range' is a schema, not an input parameter set"),
            output);
        assertTrue(output.contains("'odata-list' is an input parameter set, not a schema"),
            output);
    }

    @Test
    public void definitionsReferenceRuleShouldFireOnNamesInheritedFromObjectPrototype() {
        LintResult result = lintFixture("rules/polychro-definitions-violations.yaml");
        String output = result.output();
        assertTrue(output.contains("Unknown input parameter set 'constructor'"), output);
        assertTrue(output.contains("Unknown schema 'toString'"), output);
    }

    @Test
    public void definitionsSetConflictRuleShouldFireWhenTwoSetsDeclareTheSameInput() {
        assertRuleFires("rules/polychro-definitions-violations.yaml",
            "ikanos-definitions-set-conflict");
    }

    @Test
    public void definitionsSchemaCycleRuleShouldFireWhenSchemasReferenceEachOther() {
        assertRuleFires("rules/polychro-definitions-violations.yaml",
            "ikanos-definitions-schema-no-cycle");
    }

    @Test
    public void definitionsUnusedRuleShouldWarnOnDefinitionsNoUnitUses() {
        LintResult result = lintFixture("rules/polychro-definitions-violations.yaml");
        assertTrue(result.output().contains("ikanos-definitions-unused")
                && result.output().contains("'never-used', which no tool uses"),
            result.output());
    }

    @Test
    public void definitionsOutputKeywordsRuleShouldNoteKeywordsAnOutputDoesNotCarry() {
        LintResult result = lintFixture("rules/polychro-definitions-violations.yaml");
        String output = result.output();
        assertTrue(output.contains("ikanos-definitions-output-keywords"), output);
        assertTrue(output.contains("Schema 'order' is used in an orchestrated output, which does"
            + " not carry its keywords description, enum, format, required."), output);
        assertTrue(output.contains("Schema 'closed' is used in an orchestrated output, which does"
            + " not carry its keywords additionalProperties."), output);
    }

    @Test
    public void definitionsRulesShouldNotFireForCompliantDefinitions() {
        LintResult result = lintFixtureAtSeverity("rules/polychro-definitions-compliant.yaml",
            "warn");
        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("ikanos-definitions-"), result.output());
    }

    // ────────────────────────────────────────────────────────────────
    // Rule functions under Polychro (#1069)
    // ────────────────────────────────────────────────────────────────

    @Test
    public void ruleFunctionsShouldRunOnEveryFixture() throws IOException {
        // A function that throws is only a warning ("execution failed"), and its rule never
        // reports its own finding: Polychro passes no context (naftiko/polychro#226).
        List<Path> fixtures;
        try (Stream<Path> files = Stream.concat(
                Files.list(projectRoot.resolve("src/test/resources/rules")),
                Files.list(projectRoot.resolve("src/test/resources/imports")))) {
            fixtures = files.filter(file -> file.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(fixtures.isEmpty(), "Expected ruleset fixtures to lint.");
        StringBuilder failures = new StringBuilder();
        for (Path fixture : fixtures) {
            lint(fixture.toString(), "error").output().lines()
                .filter(line -> line.contains("execution failed"))
                .forEach(line -> failures.append(fixture.getFileName()).append(": ")
                    .append(line).append('\n'));
        }
        assertEquals("", failures.toString(), "Expected every rule function to run.");
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
    public void elicitSafetyRuleShouldCountACallToAConsumedToolThatMayAsk() {
        assertRuleFires("rules/polychro-durability-upstream-elicit.yaml",
            "ikanos-elicit-after-unsafe-requires-durability");
    }

    @Test
    public void elicitSafetyRuleShouldCountACallToAFlowThatMayAsk() {
        // The called flow asks through its singleton call to a consumed tool that may ask.
        assertRuleFires("rules/polychro-durability-nested-elicit.yaml",
            "ikanos-elicit-after-unsafe-requires-durability");
    }

    @Test
    public void elicitSafetyRuleShouldCountASqlWriteProcedureAsUnsafe() {
        // The write flow fires; the flow that reads with a SQL procedure before asking does not.
        LintResult result = lintFixture("rules/polychro-durability-sql-write-elicit.yaml");
        assertEquals(1, findings(result, "ikanos-elicit-after-unsafe-requires-durability"),
            "Expected one replay finding, on the question after the SQL write."
                + System.lineSeparator() + result.output());
        assertTrue(result.output().lines().anyMatch(line -> line.contains("ship-then-confirm")
                && line.contains("ikanos-elicit-after-unsafe-requires-durability")),
            result.output());
    }

    @Test
    public void elicitSafetyRuleShouldNotFireUnderADurableMode() {
        // Under memory and local, a question after a write resumes a journal instead of
        // replaying the write.
        LintResult result = lintFixture("rules/polychro-durability-memory-elicit.yaml");
        assertTrue(!result.output().contains("ikanos-elicit-after-unsafe-requires-durability"),
            "Expected no replay finding under the memory mode.\n" + result.output());
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
    public void restOperationsThatAskShouldNoLongerBeWarnedAbout() {
        // REST serves input requests (202, then answers POSTed to the Location).
        String output = lintFixture("rules/polychro-durability-warnings.yaml").output();
        assertTrue(!output.contains("ikanos-elicit-not-served-over-rest"),
            "Expected no REST elicit warning.\n" + output);
    }

    @Test
    public void elicitSafetyRuleShouldNotFireWhenTheQuestionComesBeforeAnyWrite() {
        String output = lintFixture("rules/polychro-durability-none-compliant.yaml").output();
        assertTrue(!output.contains("ikanos-elicit-"),
            "Expected no elicit finding when only reads precede the question.\n" + output);
    }

    @Test
    public void mcpToolInferredCallRuleShouldFireWhenNoConsumedNamespaceHasTheOperation() {
        LintResult result = lintFixture("rules/polychro-mcp-tool-inferred-call-violations.yaml");
        assertTrue(result.output().lines().anyMatch(line ->
                line.contains("ikanos-mcp-tool-inferred-call") && line.contains("get-weather")),
            "Expected ikanos-mcp-tool-inferred-call on get-weather.\n" + result.output());
    }

    @Test
    public void mcpToolInferredCallRuleShouldFireWhenSeveralConsumedNamespacesHaveTheOperation() {
        LintResult result = lintFixture("rules/polychro-mcp-tool-inferred-call-violations.yaml");
        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().lines().anyMatch(line ->
                line.contains("ikanos-mcp-tool-inferred-call") && line.contains("list-issues")),
            "Expected ikanos-mcp-tool-inferred-call on list-issues.\n" + result.output());
    }

    @Test
    public void mcpToolInferredCallRuleShouldNotFireWhenEachToolHasOneCandidate() {
        LintResult result = lintFixture("rules/polychro-mcp-tool-inferred-call-compliant.yaml");
        assertFalse(result.output().contains("ikanos-mcp-tool-inferred-call"),
            "Expected no inferred call findings for a compliant document.\n" + result.output());
    }

    @Test
    public void mcpToolInferredCallRuleShouldNotFireWhenOperationMayBeImported() {
        LintResult result = lintFixture("rules/polychro-mcp-tool-inferred-call-imported.yaml");
        assertFalse(result.output().contains("ikanos-mcp-tool-inferred-call"),
            "Expected no inferred call finding when a consumes import may hold the operation.\n"
                + result.output());
    }

    @Test
    public void definitionsReferenceRuleShouldFireOnUnknownAndWrongKindRestReferences() {
        LintResult result = lintFixture("rules/polychro-definitions-rest-violations.yaml");
        String output = result.output();
        assertTrue(result.exitCode() != 0, output);
        assertTrue(output.contains("Unknown input parameter set 'missing-in-rest'."
                + " REST adapter 'shipyard-api'"), output);
        assertTrue(output.contains("'crew-member' is a schema, not an input parameter set"),
            output);
    }

    @Test
    public void definitionsSetConflictRuleShouldFireOnARestResource() {
        LintResult result = lintFixture("rules/polychro-definitions-rest-violations.yaml");
        assertTrue(result.output().contains("ikanos-definitions-set-conflict")
                && result.output().contains("Input 'imo' comes from both input parameter sets"
                    + " 'ship-key' and 'legacy-key'"),
            result.output());
    }

    @Test
    public void definitionsSchemaCycleRuleShouldFireOnRestSchemas() {
        LintResult result = lintFixture("rules/polychro-definitions-rest-violations.yaml");
        assertTrue(result.output().contains("ikanos-definitions-schema-no-cycle")
                && result.output().contains("crew-member -> crew-lead -> crew-member"),
            result.output());
    }

    @Test
    public void definitionsUnusedRuleShouldWarnOnRestDefinitionsNoUnitUses() {
        LintResult result = lintFixture("rules/polychro-definitions-rest-violations.yaml");
        assertTrue(result.output().contains("'never-used-rest', which no resource or operation"
                + " uses"), result.output());
    }

    @Test
    public void definitionsRulesShouldNotFireForCompliantRestDefinitions() {
        LintResult result = lintFixtureAtSeverity(
            "rules/polychro-definitions-rest-compliant.yaml", "warn");
        assertFalse(result.output().contains("ikanos-definitions-"), result.output());
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
     * Assert that linting a fixture reports {@code ruleId} at {@code path}, a JSONPath from the
     * document root.
     */
    private void assertReportsAt(String fixturePath, String ruleId, String path) {
        LintResult result = lintFixture(fixturePath);
        assertTrue(result.output().lines()
                .anyMatch(line -> line.startsWith(path + "  ")
                    && line.contains("  " + ruleId + "  ")),
            "Expected " + ruleId + " at " + path + " in '" + fixturePath + "'.\n"
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
        Severity threshold = Severity.valueOf(failSeverity.toUpperCase(Locale.ROOT));
        StringBuilder output = new StringBuilder();
        boolean failed = false;
        for (Diagnostic diagnostic : diagnostics) {
            output.append(diagnostic.path()).append("  ")
                .append(diagnostic.severity().name().toLowerCase(Locale.ROOT))
                .append("  ").append(diagnostic.code()).append("  ")
                .append(diagnostic.message()).append('\n');
            failed |= diagnostic.severity().ordinal() <= threshold.ordinal();
        }
        return new LintResult(failed ? 1 : 0, output.toString());
    }

    private record LintResult(int exitCode, String output) {
    }
}
