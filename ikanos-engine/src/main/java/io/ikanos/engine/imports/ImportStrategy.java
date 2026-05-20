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
import java.util.List;

import io.ikanos.spec.IkanosSpec;

/**
 * Section-specific knowledge plugged into the generic {@link ImportResolver}.
 *
 * <p>One strategy per section (~15 lines each): {@code consumes}, {@code exposes},
 * {@code aggregates}, {@code binds}.</p>
 *
 * <p>The section's base type ({@code ClientSpec}, {@code ServerSpec}, {@code AggregateSpec},
 * {@code BindingSpec}) lives in the {@code ikanos-spec} module and cannot implement the
 * engine-level {@link Importable} interface. The strategy bridges the gap by providing
 * namespace accessors and import detection for the generic resolver.</p>
 *
 * @param <T> the section's base type (e.g. {@code ClientSpec}, {@code ServerSpec})
 */
public interface ImportStrategy<T> {

    /** Human label used in error messages: {@code "consumes"}, {@code "exposes"}, etc. */
    String sectionName();

    /**
     * Read the section array out of a parsed source {@link IkanosSpec}.
     *
     * <p>For a standalone document the section is at the root level; for a capability-wrapped
     * document it is under {@code capability}. The strategy checks both locations.</p>
     *
     * @param source the parsed YAML document
     * @return the section entries (never {@code null} — return empty list if absent)
     */
    List<T> readSection(IkanosSpec source);

    /**
     * Test whether the given entry is an import directive (as opposed to an inline definition).
     *
     * @param entry a list entry from the section
     * @return {@code true} if the entry carries import metadata ({@code from} field)
     */
    boolean isImport(T entry);

    /**
     * Extract the import directive fields from an imported entry.
     *
     * @param entry an entry for which {@link #isImport(Object)} returned {@code true}
     * @return the directive (never {@code null})
     */
    ImportDirective toDirective(T entry);

    /**
     * Return the namespace of an inline entry.
     *
     * @param entry an inline entry
     * @return the namespace string (may be {@code null})
     */
    String getNamespace(T entry);

    /**
     * Set the namespace on a resolved inline entry (used to apply the {@code as} alias).
     *
     * @param entry     the resolved entry
     * @param namespace the new namespace value
     */
    void setNamespace(T entry, String namespace);

    /**
     * Deep-copy an inline entry so that mutations in one capability do not affect another
     * capability that imported the same source entry.
     *
     * <p>Implementation: Jackson round-trip (serialize → deserialize).</p>
     *
     * @param inline the resolved inline entry to copy
     * @return an independent deep copy
     * @throws IOException if serialization fails
     */
    T deepCopy(T inline) throws IOException;
}
