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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.aggregates.ImportedAggregateSpec;

/**
 * Import strategy for the {@code aggregates} section.
 */
public class AggregatesImportStrategy implements ImportStrategy<AggregateSpec> {

    @Override
    public String sectionName() {
        return "aggregates";
    }

    @Override
    public List<AggregateSpec> readSection(IkanosSpec source) {
        // Capability document: its sections live under capability only. A root section next
        // to capability is not valid there (#955) and is never imported.
        if (source.getCapability() != null) {
            Map<String, AggregateSpec> section = source.getCapability().getAggregates();
            return section != null ? new ArrayList<>(section.values()) : Collections.emptyList();
        }
        // Standalone file: the section is at the root
        if (source.getAggregates() != null && !source.getAggregates().isEmpty()) {
            return source.getAggregates();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isImport(AggregateSpec entry) {
        return entry instanceof ImportedAggregateSpec;
    }

    @Override
    public ImportDirective toDirective(AggregateSpec entry) {
        ImportedAggregateSpec imp = (ImportedAggregateSpec) entry;
        return new ImportDirective(imp.getFrom(), imp.getImportNamespace(),
                imp.getAlias(), imp.getDescription());
    }

    @Override
    public String getNamespace(AggregateSpec entry) {
        return entry.getNamespace();
    }

    @Override
    public void setNamespace(AggregateSpec entry, String namespace) {
        entry.setNamespace(namespace);
    }

    @Override
    public AggregateSpec deepCopy(AggregateSpec inline, SourceFileLoader loader) throws IOException {
        return loader.deepCopy(inline, AggregateSpec.class);
    }
}
