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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.ikanos.spec.IkanosSpec;

/**
 * Generic import resolver shared by all four sections ({@code consumes}, {@code exposes},
 * {@code aggregates}, {@code binds}).
 *
 * <p>The resolver iterates over a section's entry list, detects import directives via the
 * strategy, loads the source file, finds the matching namespace, deep-copies the entry,
 * applies the alias, and replaces the import entry in-place. After resolution the list
 * contains only fully-materialized inline entries — no {@code Imported*Spec} instance
 * escapes the capability constructor.</p>
 *
 * <p>See {@code blueprints/unified-import-mechanism.md §9} for the full design.</p>
 *
 * @param <T> the section's base type (e.g. {@code ClientSpec}, {@code ServerSpec})
 */
public final class ImportResolver<T> {

    private final ImportStrategy<T> strategy;
    private final SourceFileLoader loader;

    public ImportResolver(ImportStrategy<T> strategy, SourceFileLoader loader) {
        this.strategy = strategy;
        this.loader = loader;
    }

    /**
     * Replace every import entry in {@code entries} with its resolved inline entry.
     *
     * @param entries       the section array from the capability (mutated in-place)
     * @param capabilityDir the directory containing the importing capability file
     * @throws ImportException if any import cannot be resolved
     */
    public void resolveAll(List<T> entries, Path capabilityDir) throws ImportException {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            T entry = entries.get(i);
            if (!strategy.isImport(entry)) {
                continue;
            }

            T resolved = resolveOne(strategy.toDirective(entry), capabilityDir);
            entries.set(i, resolved);
        }

        assertNoNamespaceCollisions(entries);
    }

    /**
     * Resolve a single import directive into a fully-materialized inline entry.
     */
    private T resolveOne(ImportDirective d, Path capabilityDir) throws ImportException {
        requireNonEmpty(d.from(), "from");
        requireNonEmpty(d.importNamespace(), "import");

        Path src = resolvePath(d.from(), capabilityDir);
        if (!Files.exists(src)) {
            throw new ImportException(strategy.sectionName(),
                    "Import source file not found: " + src);
        }

        IkanosSpec sourceSpec;
        try {
            sourceSpec = loader.load(src);
        } catch (IOException e) {
            throw new ImportException(strategy.sectionName(),
                    "Failed to load source file: " + src, e);
        }

        List<T> section = strategy.readSection(sourceSpec);
        if (section == null || section.isEmpty()) {
            throw new ImportException(strategy.sectionName(),
                    String.format("No %s entries found in source file: %s",
                            strategy.sectionName(), src));
        }

        T match = section.stream()
                .filter(e -> !strategy.isImport(e))
                .filter(e -> d.importNamespace().equals(strategy.getNamespace(e)))
                .findFirst()
                .orElseThrow(() -> new ImportException(strategy.sectionName(),
                        String.format("Namespace '%s' not found in source %s file: %s",
                                d.importNamespace(), strategy.sectionName(), src)));

        T copy;
        try {
            copy = strategy.deepCopy(match);
        } catch (IOException e) {
            throw new ImportException(strategy.sectionName(),
                    "Failed to deep-copy resolved entry: " + d.importNamespace(), e);
        }

        if (d.alias() != null && !d.alias().isEmpty()) {
            strategy.setNamespace(copy, d.alias());
        }

        return copy;
    }

    /**
     * Resolve a file path relative to the capability directory.
     */
    Path resolvePath(String from, Path capabilityDir) {
        Path basePath = (capabilityDir != null)
                ? capabilityDir
                : Paths.get(".");

        return basePath.resolve(from).normalize().toAbsolutePath();
    }

    /**
     * Validate that a required directive field is present and non-empty.
     */
    private void requireNonEmpty(String value, String fieldName) throws ImportException {
        if (value == null || value.isEmpty()) {
            throw new ImportException(strategy.sectionName(),
                    "Import '" + fieldName + "' is required");
        }
    }

    /**
     * After resolution, assert that no two entries in the section share the same namespace.
     */
    private void assertNoNamespaceCollisions(List<T> entries) throws ImportException {
        Set<String> seen = new HashSet<>();
        for (T entry : entries) {
            String ns = strategy.getNamespace(entry);
            if (ns != null && !seen.add(ns)) {
                throw new ImportException(strategy.sectionName(),
                        "Duplicate namespace '" + ns + "' after import resolution");
            }
        }
    }
}
