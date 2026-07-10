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
package io.ikanos.spec.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * A globally imported binding reference.
 *
 * <p>Discriminant: presence of the {@code from} field on a list entry distinguishes an import
 * from an inline {@link BindingSpec}. The {@code from} field is the parse-time path to the
 * source binds file; it does <em>not</em> collide with {@link BindingSpec#getLocation()}, which
 * is the runtime variable-source URI of an inline binding (e.g. {@code file:///.env},
 * {@code vault://...}).</p>
 *
 * <p>Follows the unified import directive shared by {@code consumes}, {@code exposes},
 * {@code aggregates}, and {@code binds}: {@code from} / {@code import} / {@code as} /
 * {@code description}. See {@code blueprints/unified-import-mechanism.md}.</p>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ImportedBindingSpec extends BindingSpec {

    @JsonProperty("from")
    private volatile String from;

    @JsonProperty("import")
    private volatile String importNamespace;

    @JsonProperty("as")
    private volatile String alias;

    @JsonProperty("description")
    private volatile String description;

    public ImportedBindingSpec() {
        super();
    }

    public ImportedBindingSpec(String from, String importNamespace, String alias) {
        super();
        this.from = from;
        this.importNamespace = importNamespace;
        this.alias = alias;
    }

    public ImportedBindingSpec(String from, String importNamespace, String alias, String description) {
        super();
        this.from = from;
        this.importNamespace = importNamespace;
        this.alias = alias;
        this.description = description;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getImportNamespace() {
        return importNamespace;
    }

    public void setImportNamespace(String importNamespace) {
        this.importNamespace = importNamespace;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the effective namespace for this import: the alias if set, otherwise the source
     * namespace. May be called only after the import has been parsed (both fields populated).
     */
    public String getEffectiveNamespace() {
        if (importNamespace == null) {
            throw new IllegalStateException(
                "Cannot get namespace before import is resolved. from: " + from
            );
        }
        return (alias != null && !alias.isEmpty()) ? alias : importNamespace;
    }
}
