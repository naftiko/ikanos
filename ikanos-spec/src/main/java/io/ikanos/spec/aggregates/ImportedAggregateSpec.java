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
package io.ikanos.spec.aggregates;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * A globally imported aggregate reference.
 *
 * <p>Discriminant: presence of the {@code from} field on a list entry distinguishes an import
 * from an inline {@link AggregateSpec}. Until import resolution (Phase 2) replaces this entry
 * with a fully-materialized {@link AggregateSpec}, only the import directive fields are
 * meaningful.</p>
 *
 * <p>Follows the unified import directive shared by {@code consumes}, {@code exposes},
 * {@code aggregates}, and {@code binds}: {@code from} / {@code import} / {@code as} /
 * {@code description}. See {@code blueprints/unified-import-mechanism.md}.</p>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ImportedAggregateSpec extends AggregateSpec {

    @JsonProperty("from")
    private volatile String from;

    @JsonProperty("import")
    private volatile String importNamespace;

    @JsonProperty("as")
    private volatile String alias;

    @JsonProperty("description")
    private volatile String description;

    public ImportedAggregateSpec() {
        super();
    }

    public ImportedAggregateSpec(String from, String importNamespace, String alias) {
        super();
        this.from = from;
        this.importNamespace = importNamespace;
        this.alias = alias;
    }

    public ImportedAggregateSpec(String from, String importNamespace, String alias, String description) {
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

    public String getDescription() {
        return description;
    }

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
