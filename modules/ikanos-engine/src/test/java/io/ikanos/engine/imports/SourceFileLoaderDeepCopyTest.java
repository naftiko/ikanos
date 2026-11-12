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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.assertj.core.api.recursive.comparison.ComparingFields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.aggregates.ImportedAggregateSpec;
import io.ikanos.spec.exposes.ImportedExposesSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * An import copies the entry it resolves through YAML ({@link SourceFileLoader#deepCopy}). The copy
 * must read back as the entry it was made from: derived getters must not be written out, and
 * {@code required: false} must survive.
 */
class SourceFileLoaderDeepCopyTest {

    private static final Path MCP_FIXTURES = Path.of("src/test/resources/mcp");

    /**
     * Capability files bundled with the spec and the docs, and every engine test fixture, so the
     * net covers every adapter type.
     */
    private static final List<Path> BUNDLED = List.of(
            Path.of("../ikanos-spec/src/main/resources/schemas/examples"),
            Path.of("../ikanos-docs/tutorial"),
            Path.of("src/test/resources"));

    /**
     * Compares fields, not getters: a getter can hide the field of the same name, as
     * {@code InputParameterSpec.getRequired()} (the required-property list) hides the
     * {@code required} flag. Atomic holders are unwrapped so that their content is compared, not
     * their identity.
     */
    private static final ComparingFields FIELDS = new ComparingFields() {
        @Override
        public Object getChildNodeValue(String name, Object instance) {
            Object value = super.getChildNodeValue(name, instance);
            if (value instanceof AtomicReference<?> reference) {
                return reference.get();
            }
            if (value instanceof AtomicBoolean flag) {
                return flag.get();
            }
            if (value instanceof AtomicInteger number) {
                return number.get();
            }
            if (value instanceof AtomicLong number) {
                return number.get();
            }
            return value;
        }
    };

    private SourceFileLoader loader;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        loader = new SourceFileLoader();
        tempDir = Files.createTempDirectory("ikanos-deep-copy-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // ignore
                }
            });
        }
    }

    @Test
    void importedMcpAdapterShouldKeepItsToolsResourcesAndPrompts() throws Exception {
        Path source = MCP_FIXTURES.resolve("mcp-resources-prompts-capability.yaml").toAbsolutePath();
        McpServerSpec original = mcpAdapter(loader.load(source));

        McpServerSpec imported = importMcpAdapter(source, "test-mcp-resources-prompts");

        assertEquals(original.getTools().keySet(), imported.getTools().keySet());
        assertEquals(original.getResources().keySet(), imported.getResources().keySet());
        assertEquals(original.getPrompts().keySet(), imported.getPrompts().keySet());
        assertFalse(imported.getTools().isEmpty());
        assertFalse(imported.getResources().isEmpty());
        assertFalse(imported.getPrompts().isEmpty());
    }

    @Test
    void importedStdioMcpAdapterShouldStayStdio() throws Exception {
        Path source = MCP_FIXTURES.resolve("mcp-stdio-capability.yaml").toAbsolutePath();

        McpServerSpec imported = importMcpAdapter(source, "test-mcp-stdio");

        assertTrue(imported.isStdio());
        assertEquals("stdio", imported.getTransport());
    }

    @Test
    void importedMcpToolShouldKeepOptionalInputsOptional() throws Exception {
        Path source = tempDir.resolve("tools.exposes.yml");
        Files.writeString(source, """
                ikanos: "%s"
                exposes:
                  - type: "mcp"
                    address: "localhost"
                    port: 9301
                    namespace: "tools"
                    tools:
                      list-ships:
                        description: "List ships."
                        inputParameters:
                          imo:
                            type: "string"
                            required: true
                            description: "IMO number."
                          sort:
                            type: "string"
                            description: "Sort order."
                          page:
                            type: "integer"
                            required: false
                            description: "Page number."
                        call: "registry.list-ships"
                """.formatted(VersionHelper.getSchemaVersion()));

        McpServerToolSpec tool = importMcpAdapter(source, "tools").getTools().get("list-ships");

        assertTrue(input(tool.getInputParameters(), "imo").isRequired());
        assertFalse(input(tool.getInputParameters(), "sort").isRequired(),
                "an input without a required flag stays optional (#983)");
        assertFalse(input(tool.getInputParameters(), "page").isRequired(),
                "required: false must survive the import copy");
    }

    @Test
    void importedAggregateShouldKeepOptionalInputsOptional() throws Exception {
        Path source = tempDir.resolve("fleet.aggregates.yml");
        Files.writeString(source, """
                ikanos: "%s"
                aggregates:
                  - namespace: "fleet"
                    display: "Fleet"
                    flows:
                      list-ships:
                        description: "List ships."
                        inputParameters:
                          imo:
                            type: "string"
                            required: true
                            description: "IMO number."
                          sort:
                            type: "string"
                            description: "Sort order."
                          page:
                            type: "integer"
                            required: false
                            description: "Page number."
                        call: "registry.list-ships"
                """.formatted(VersionHelper.getSchemaVersion()));

        List<AggregateSpec> entries = new ArrayList<>();
        entries.add(new ImportedAggregateSpec("./fleet.aggregates.yml", "fleet", null));
        new ImportResolver<>(new AggregatesImportStrategy(), loader).resolveAll(entries, tempDir);

        AggregateFlowSpec flow = entries.get(0).getFlows().get("list-ships");
        assertTrue(flow.getInputParameters().get("imo").isRequired());
        assertFalse(flow.getInputParameters().get("sort").isRequired(),
                "an input without a required flag stays optional (#983)");
        assertFalse(flow.getInputParameters().get("page").isRequired(),
                "required: false must survive the import copy");
    }

    /**
     * Regression net: every entry of every bundled capability survives the copy unchanged. The copy
     * is compared field by field with the entry it was made from, not with its own serialized form,
     * so a field the serializer drops shows up as a difference instead of being dropped on both
     * sides. A field the entry leaves unset may come back set to its default (the copy writes what
     * the getter returns, such as {@code transport: http}); a field the entry sets must come back
     * with the same value.
     */
    @Test
    void everyBundledEntryShouldSurviveTheImportCopy() throws IOException {
        List<String> failures = new ArrayList<>();
        int copied = 0;
        for (Path dir : BUNDLED) {
            for (Path file : capabilityFiles(dir)) {
                IkanosSpec spec;
                try {
                    spec = loader.load(file.toAbsolutePath());
                } catch (IOException e) {
                    continue; // not a loadable capability: a fixture meant to fail, a fragment
                }
                for (Object entry : entries(spec)) {
                    String where = file + " " + entry.getClass().getSimpleName();
                    try {
                        Object copy = loader.deepCopy(entry, entry.getClass());
                        copied++;
                        assertThat(copy).usingRecursiveComparison()
                                .withIntrospectionStrategy(FIELDS)
                                .ignoringExpectedNullFields()
                                .isEqualTo(entry);
                    } catch (IOException | RuntimeException | AssertionError e) {
                        failures.add(where + ": " + String.valueOf(e.getMessage()).strip()
                                .replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertTrue(copied > 0, "no bundled entry was found");
        assertEquals(List.of(), failures);
    }

    private McpServerSpec importMcpAdapter(Path source, String namespace) throws ImportException {
        List<ServerSpec> entries = new ArrayList<>();
        entries.add(new ImportedExposesSpec(source.getFileName().toString(), namespace, null));
        new ImportResolver<>(new ExposesImportStrategy(), loader)
                .resolveAll(entries, source.getParent());
        return assertInstanceOf(McpServerSpec.class, entries.get(0));
    }

    private static McpServerSpec mcpAdapter(IkanosSpec spec) {
        return spec.getCapability().getExposes().stream()
                .filter(McpServerSpec.class::isInstance).map(McpServerSpec.class::cast)
                .findFirst().orElseThrow();
    }

    private static InputParameterSpec input(List<InputParameterSpec> params, String name) {
        return params.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }

    private static List<Path> capabilityFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(p -> p.toString().matches(".*\\.ya?ml$")).sorted().toList();
        }
    }

    private static List<Object> entries(IkanosSpec spec) {
        List<Object> entries = new ArrayList<>();
        if (spec.getCapability() != null) {
            var capability = spec.getCapability();
            if (capability.getConsumes() != null) {
                entries.addAll(capability.getConsumes());
            }
            if (capability.getExposes() != null) {
                entries.addAll(capability.getExposes());
            }
            if (capability.getAggregates() != null) {
                entries.addAll(capability.getAggregates().values());
            }
            if (capability.getBinds() != null) {
                entries.addAll(capability.getBinds());
            }
        }
        if (spec.getConsumes() != null) {
            entries.addAll(spec.getConsumes());
        }
        if (spec.getAggregates() != null) {
            entries.addAll(spec.getAggregates());
        }
        if (spec.getBinds() != null) {
            entries.addAll(spec.getBinds());
        }
        return entries;
    }
}
