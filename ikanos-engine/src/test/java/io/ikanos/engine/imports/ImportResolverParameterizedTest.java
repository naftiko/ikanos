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
package io.ikanos.engine.imports;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.aggregates.ImportedAggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import io.ikanos.spec.exposes.ImportedExposesSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.util.ImportedBindingSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Parameterized test suite for the unified {@link ImportResolver}, exercising all four
 * sections ({@code consumes}, {@code exposes}, {@code aggregates}, {@code binds}) through
 * the same set of scenarios.
 *
 * <p>Covers §12.3 of {@code blueprints/unified-import-mechanism.md}.</p>
 */
class ImportResolverParameterizedTest {

    private Path tempDir;
    private String schemaVersion;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ikanos-import-resolver-test-");
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // ignore
                    }
                });
    }

    // ─── Scenario providers ────────────────────────────────────────────

    static Stream<Arguments> allSections() {
        return Stream.of(
                Arguments.of("consumes"),
                Arguments.of("exposes"),
                Arguments.of("aggregates"),
                Arguments.of("binds")
        );
    }

    // ─── §12.3 #1 — Simple import ──────────────────────────────────────

    @ParameterizedTest(name = "[{0}] simple import resolves namespace")
    @MethodSource("allSections")
    void simpleImportShouldResolveNamespace(String section) throws Exception {
        writeSourceFile(section, "shared", "test-ns", null);

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./shared." + section + ".yml", "test-ns", null);
        harness.resolve(tempDir);

        assertEquals(1, harness.size());
        assertEquals("test-ns", harness.getNamespace(0));
        assertFalse(harness.isImport(0), "Resolved entry should be inline, not import");
    }

    // ─── §12.3 #2 — Import with alias ─────────────────────────────────

    @ParameterizedTest(name = "[{0}] import with alias renames namespace")
    @MethodSource("allSections")
    void importWithAliasShouldRenameNamespace(String section) throws Exception {
        writeSourceFile(section, "shared", "original-ns", null);

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./shared." + section + ".yml", "original-ns", "aliased-ns");
        harness.resolve(tempDir);

        assertEquals(1, harness.size());
        assertEquals("aliased-ns", harness.getNamespace(0));
    }

    // ─── §12.3 #3 — Two imports, same source namespace, one with alias ─

    @ParameterizedTest(name = "[{0}] two imports of same namespace with alias coexist")
    @MethodSource("allSections")
    void twoImportsSameNamespaceWithAliasShouldCoexist(String section) throws Exception {
        writeSourceFile(section, "file-a", "shared-ns", null);
        writeSourceFile(section, "file-b", "shared-ns", null);

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./file-a." + section + ".yml", "shared-ns", "ns-a");
        harness.addImport("./file-b." + section + ".yml", "shared-ns", "ns-b");
        harness.resolve(tempDir);

        assertEquals(2, harness.size());
        assertEquals("ns-a", harness.getNamespace(0));
        assertEquals("ns-b", harness.getNamespace(1));
    }

    // ─── §12.3 #4 — Missing from ──────────────────────────────────────

    @ParameterizedTest(name = "[{0}] missing 'from' throws ImportException")
    @MethodSource("allSections")
    void missingFromShouldThrow(String section) {
        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport(null, "some-ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains(section), "Error should name section");
        assertTrue(ex.getMessage().contains("from"), "Error should name field");
    }

    // ─── §12.3 #5 — Missing import ────────────────────────────────────

    @ParameterizedTest(name = "[{0}] missing 'import' throws ImportException")
    @MethodSource("allSections")
    void missingImportShouldThrow(String section) {
        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./some-file.yml", null, null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains(section), "Error should name section");
        assertTrue(ex.getMessage().contains("import"), "Error should name field");
    }

    // ─── §12.3 #6 — Nonexistent file ──────────────────────────────────

    @ParameterizedTest(name = "[{0}] nonexistent file throws ImportException")
    @MethodSource("allSections")
    void nonexistentFileShouldThrow(String section) {
        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./no-such-file.yml", "ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains("not found"), "Error should say 'not found'");
    }

    // ─── §12.3 #7 — Malformed YAML ────────────────────────────────────

    @ParameterizedTest(name = "[{0}] malformed YAML throws ImportException")
    @MethodSource("allSections")
    void malformedYamlShouldThrow(String section) throws Exception {
        Files.writeString(tempDir.resolve("bad." + section + ".yml"),
                "this is not valid yaml: [[[unterminated\n  - broken: {");

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./bad." + section + ".yml", "ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains("Failed to load"), "Error should describe load failure");
    }

    // ─── §12.3 #8 — Source file has no entries in section ──────────────

    @ParameterizedTest(name = "[{0}] empty section in source file throws ImportException")
    @MethodSource("allSections")
    void emptySourceSectionShouldThrow(String section) throws Exception {
        // Write a valid YAML with an empty section
        String yaml = "ikanos: \"" + schemaVersion + "\"\n" + section + ": []\n";
        Files.writeString(tempDir.resolve("empty." + section + ".yml"), yaml);

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./empty." + section + ".yml", "ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains("No " + section + " entries"),
                "Error should describe empty section");
    }

    // ─── §12.3 #9 — Namespace not found ───────────────────────────────

    @ParameterizedTest(name = "[{0}] namespace not found in source throws ImportException")
    @MethodSource("allSections")
    void namespaceNotFoundShouldThrow(String section) throws Exception {
        writeSourceFile(section, "shared", "existing-ns", null);

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./shared." + section + ".yml", "wrong-ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains("wrong-ns"), "Error should name the missing namespace");
    }

    // ─── §12.3 #10 — Capability-wrapped source file ───────────────────

    @ParameterizedTest(name = "[{0}] capability-wrapped source file resolves")
    @MethodSource("allSections")
    void capabilityWrappedSourceShouldResolve(String section) throws Exception {
        writeCapabilityWrappedSourceFile(section, "wrapped", "wrapped-ns");

        SectionTestHarness<?> harness = createHarness(section);
        harness.addImport("./wrapped." + section + ".yml", "wrapped-ns", null);
        harness.resolve(tempDir);

        assertEquals(1, harness.size());
        assertEquals("wrapped-ns", harness.getNamespace(0));
    }

    // ─── §12.3 #11 — Duplicate namespace collision ────────────────────

    @ParameterizedTest(name = "[{0}] duplicate namespace after resolution throws")
    @MethodSource("allSections")
    void duplicateNamespaceShouldThrow(String section) throws Exception {
        writeSourceFile(section, "file-a", "collision-ns", null);
        writeSourceFile(section, "file-b", "collision-ns", null);

        SectionTestHarness<?> harness = createHarness(section);
        // Both imports resolve to the same namespace without alias
        harness.addImport("./file-a." + section + ".yml", "collision-ns", null);
        harness.addImport("./file-b." + section + ".yml", "collision-ns", null);

        ImportException ex = assertThrows(ImportException.class, () -> harness.resolve(tempDir));
        assertTrue(ex.getMessage().contains("Duplicate namespace"),
                "Error should describe collision");
    }

    // ─── §12.3 #13 — Deep-copy isolation ──────────────────────────────

    @Test
    void deepCopyIsolationShouldPreventMutationAcrossCapabilities() throws Exception {
        writeSourceFile("consumes", "shared", "api", null);

        SourceFileLoader loader = new SourceFileLoader();
        ImportResolver<ClientSpec> resolver = new ImportResolver<>(
                new ConsumesImportStrategy(), loader);

        // First capability imports and mutates
        List<ClientSpec> list1 = new ArrayList<>();
        list1.add(new ImportedConsumesHttpSpec("./shared.consumes.yml", "api", null));
        resolver.resolveAll(list1, tempDir);
        ((HttpClientSpec) list1.get(0)).setBaseUri("https://mutated.example.com");

        // Second capability imports the same source — should be unaffected
        ImportResolver<ClientSpec> resolver2 = new ImportResolver<>(
                new ConsumesImportStrategy(), new SourceFileLoader());
        List<ClientSpec> list2 = new ArrayList<>();
        list2.add(new ImportedConsumesHttpSpec("./shared.consumes.yml", "api", null));
        resolver2.resolveAll(list2, tempDir);

        assertNotEquals(
                ((HttpClientSpec) list1.get(0)).getBaseUri(),
                ((HttpClientSpec) list2.get(0)).getBaseUri(),
                "Deep-copy should prevent cross-capability mutation");
    }

    // ─── §12.3 #5 (mixed) — Inline + imported coexist ─────────────────

    @Test
    void mixedInlineAndImportedConsumesShouldCoexist() throws Exception {
        writeSourceFile("consumes", "external", "external-api", null);

        SourceFileLoader loader = new SourceFileLoader();
        ImportResolver<ClientSpec> resolver = new ImportResolver<>(
                new ConsumesImportStrategy(), loader);

        List<ClientSpec> consumes = new ArrayList<>();
        HttpClientSpec inline = new HttpClientSpec();
        inline.setNamespace("local-api");
        inline.setBaseUri("http://localhost:9999");
        consumes.add(inline);
        consumes.add(new ImportedConsumesHttpSpec("./external.consumes.yml", "external-api", null));

        resolver.resolveAll(consumes, tempDir);

        assertEquals(2, consumes.size());
        assertTrue(consumes.get(0) instanceof HttpClientSpec);
        assertTrue(consumes.get(1) instanceof HttpClientSpec);
        assertEquals("local-api", consumes.get(0).getNamespace());
        assertEquals("external-api", consumes.get(1).getNamespace());
    }

    // ─── §12.3 — Null and empty list ──────────────────────────────────

    @ParameterizedTest(name = "[{0}] empty list is noop")
    @MethodSource("allSections")
    void emptyListShouldBeNoop(String section) throws Exception {
        SectionTestHarness<?> harness = createHarness(section);
        // resolve empty — should not throw
        harness.resolve(tempDir);
        assertEquals(0, harness.size());
    }

    @Test
    void nullListShouldBeNoop() throws Exception {
        SourceFileLoader loader = new SourceFileLoader();
        ImportResolver<ClientSpec> resolver = new ImportResolver<>(
                new ConsumesImportStrategy(), loader);
        // Should not throw
        resolver.resolveAll(null, tempDir);
    }

    // ─── Null-namespace tolerance ──────────────────────────────────────

    @Test
    void twoEntriesWithNullNamespaceShouldNotCollide() throws Exception {
        SourceFileLoader loader = new SourceFileLoader();
        ImportResolver<BindingSpec> resolver = new ImportResolver<>(
                new BindsImportStrategy(), loader);

        List<BindingSpec> entries = new ArrayList<>();
        // Two inline entries without namespace — should not trigger collision
        BindingSpec b1 = new BindingSpec();
        b1.setDescription("binding-one");
        b1.setLocation("./a.env");
        entries.add(b1);
        BindingSpec b2 = new BindingSpec();
        b2.setDescription("binding-two");
        b2.setLocation("./b.env");
        entries.add(b2);

        // Should not throw — null namespaces are exempt from collision detection
        resolver.resolveAll(entries, tempDir);
        assertEquals(2, entries.size());
        assertNull(entries.get(0).getNamespace());
        assertNull(entries.get(1).getNamespace());
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    /**
     * Write a standalone source file for the given section with one entry.
     */
    private void writeSourceFile(String section, String prefix, String namespace,
            String extraFields) throws IOException {
        String yaml = switch (section) {
            case "consumes" -> """
                    ikanos: "%s"
                    consumes:
                      - type: "http"
                        namespace: "%s"
                        baseUri: "https://%s.example.com"
                        resources: []
                    """.formatted(schemaVersion, namespace, namespace);
            case "exposes" -> """
                    ikanos: "%s"
                    exposes:
                      - type: "rest"
                        namespace: "%s"
                        address: "localhost"
                        port: 8080
                        resources: []
                    """.formatted(schemaVersion, namespace);
            case "aggregates" -> """
                    ikanos: "%s"
                    aggregates:
                      - namespace: "%s"
                        label: "Test Aggregate"
                        functions: []
                    """.formatted(schemaVersion, namespace);
            case "binds" -> """
                    ikanos: "%s"
                    binds:
                      - namespace: "%s"
                        description: "Test binding"
                        location: "./test.env"
                    """.formatted(schemaVersion, namespace);
            default -> throw new IllegalArgumentException("Unknown section: " + section);
        };

        Files.writeString(tempDir.resolve(prefix + "." + section + ".yml"), yaml);
    }

    /**
     * Write a capability-wrapped source file (fallback path).
     */
    private void writeCapabilityWrappedSourceFile(String section, String prefix,
            String namespace) throws IOException {
        String inner = switch (section) {
            case "consumes" -> """
                        consumes:
                          - type: "http"
                            namespace: "%s"
                            baseUri: "https://%s.example.com"
                            resources: []
                    """.formatted(namespace, namespace);
            case "exposes" -> """
                        exposes:
                          - type: "rest"
                            namespace: "%s"
                            address: "localhost"
                            port: 9090
                            resources: []
                    """.formatted(namespace);
            case "aggregates" -> """
                        aggregates:
                          - namespace: "%s"
                            label: "Wrapped Aggregate"
                            functions: []
                    """.formatted(namespace);
            case "binds" -> """
                        binds:
                          - namespace: "%s"
                            description: "Wrapped binding"
                            location: "./wrapped.env"
                    """.formatted(namespace);
            default -> throw new IllegalArgumentException("Unknown section: " + section);
        };

        String yaml = "ikanos: \"" + schemaVersion + "\"\ncapability:\n" + inner;
        Files.writeString(tempDir.resolve(prefix + "." + section + ".yml"), yaml);
    }

    /**
     * Creates a section-appropriate test harness that wraps the list + resolver + strategy.
     */
    private SectionTestHarness<?> createHarness(String section) {
        SourceFileLoader loader = new SourceFileLoader();
        return switch (section) {
            case "consumes" -> new ConsumesSectionHarness(loader);
            case "exposes" -> new ExposesSectionHarness(loader);
            case "aggregates" -> new AggregatesSectionHarness(loader);
            case "binds" -> new BindsSectionHarness(loader);
            default -> throw new IllegalArgumentException("Unknown section: " + section);
        };
    }

    // ─── Section test harnesses ────────────────────────────────────────

    /** Type-erased interface for parameterized test interaction. */
    interface SectionTestHarness<T> {
        void addImport(String from, String importNs, String alias);
        void resolve(Path capabilityDir) throws ImportException;
        int size();
        String getNamespace(int index);
        boolean isImport(int index);
    }

    static class ConsumesSectionHarness implements SectionTestHarness<ClientSpec> {
        private final List<ClientSpec> entries = new ArrayList<>();
        private final ImportResolver<ClientSpec> resolver;
        private final ConsumesImportStrategy strategy = new ConsumesImportStrategy();

        ConsumesSectionHarness(SourceFileLoader loader) {
            this.resolver = new ImportResolver<>(strategy, loader);
        }

        @Override
        public void addImport(String from, String importNs, String alias) {
            entries.add(new ImportedConsumesHttpSpec(from, importNs, alias));
        }

        @Override
        public void resolve(Path dir) throws ImportException {
            resolver.resolveAll(entries, dir);
        }

        @Override
        public int size() { return entries.size(); }

        @Override
        public String getNamespace(int i) { return strategy.getNamespace(entries.get(i)); }

        @Override
        public boolean isImport(int i) { return strategy.isImport(entries.get(i)); }
    }

    static class ExposesSectionHarness implements SectionTestHarness<ServerSpec> {
        private final List<ServerSpec> entries = new ArrayList<>();
        private final ImportResolver<ServerSpec> resolver;
        private final ExposesImportStrategy strategy = new ExposesImportStrategy();

        ExposesSectionHarness(SourceFileLoader loader) {
            this.resolver = new ImportResolver<>(strategy, loader);
        }

        @Override
        public void addImport(String from, String importNs, String alias) {
            entries.add(new ImportedExposesSpec(from, importNs, alias));
        }

        @Override
        public void resolve(Path dir) throws ImportException {
            resolver.resolveAll(entries, dir);
        }

        @Override
        public int size() { return entries.size(); }

        @Override
        public String getNamespace(int i) { return strategy.getNamespace(entries.get(i)); }

        @Override
        public boolean isImport(int i) { return strategy.isImport(entries.get(i)); }
    }

    static class AggregatesSectionHarness implements SectionTestHarness<AggregateSpec> {
        private final List<AggregateSpec> entries = new ArrayList<>();
        private final ImportResolver<AggregateSpec> resolver;
        private final AggregatesImportStrategy strategy = new AggregatesImportStrategy();

        AggregatesSectionHarness(SourceFileLoader loader) {
            this.resolver = new ImportResolver<>(strategy, loader);
        }

        @Override
        public void addImport(String from, String importNs, String alias) {
            entries.add(new ImportedAggregateSpec(from, importNs, alias));
        }

        @Override
        public void resolve(Path dir) throws ImportException {
            resolver.resolveAll(entries, dir);
        }

        @Override
        public int size() { return entries.size(); }

        @Override
        public String getNamespace(int i) { return strategy.getNamespace(entries.get(i)); }

        @Override
        public boolean isImport(int i) { return strategy.isImport(entries.get(i)); }
    }

    static class BindsSectionHarness implements SectionTestHarness<BindingSpec> {
        private final List<BindingSpec> entries = new ArrayList<>();
        private final ImportResolver<BindingSpec> resolver;
        private final BindsImportStrategy strategy = new BindsImportStrategy();

        BindsSectionHarness(SourceFileLoader loader) {
            this.resolver = new ImportResolver<>(strategy, loader);
        }

        @Override
        public void addImport(String from, String importNs, String alias) {
            entries.add(new ImportedBindingSpec(from, importNs, alias));
        }

        @Override
        public void resolve(Path dir) throws ImportException {
            resolver.resolveAll(entries, dir);
        }

        @Override
        public int size() { return entries.size(); }

        @Override
        public String getNamespace(int i) { return strategy.getNamespace(entries.get(i)); }

        @Override
        public boolean isImport(int i) { return strategy.isImport(entries.get(i)); }
    }
}
