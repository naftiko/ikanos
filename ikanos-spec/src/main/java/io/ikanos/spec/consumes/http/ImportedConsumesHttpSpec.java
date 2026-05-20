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
package io.ikanos.spec.consumes.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * A globally imported consumed HTTP adapter reference.
 * Discriminant: presence of the 'from' field distinguishes from HttpClientSpec.
 *
 * <p>When an import is resolved, this spec should be replaced by the resolved HttpClientSpec
 * from the source consumes file.</p>
 *
 * <p>This entry follows the unified import directive shared by {@code consumes}, {@code exposes},
 * {@code aggregates}, and {@code binds}: {@code from} / {@code import} / {@code as} /
 * {@code description}. See {@code blueprints/unified-import-mechanism.md}.</p>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ImportedConsumesHttpSpec extends ClientSpec {

    @JsonProperty("from")
    private volatile String from;

    @JsonProperty("import")
    private volatile String importNamespace;

    @JsonProperty("as")
    private volatile String alias;

    @JsonProperty("description")
    private volatile String description;

    public ImportedConsumesHttpSpec() {
        super(null, null);
    }

    public ImportedConsumesHttpSpec(String from, String importNamespace, String alias) {
        super("http", null);
        this.from = from;
        this.importNamespace = importNamespace;
        this.alias = alias;
    }

    public ImportedConsumesHttpSpec(String from, String importNamespace, String alias, String description) {
        super("http", null);
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
     * Returns the effective namespace for this import.
     * If 'as' is specified, uses that; otherwise uses the source namespace.
     * This should only be called after import resolution.
     */
    @Override
    public String getNamespace() {
        if (importNamespace == null) {
            throw new IllegalStateException(
                "Cannot get namespace before import is resolved. from: " + from
            );
        }
        return (alias != null && !alias.isEmpty()) ? alias : importNamespace;
    }
}
