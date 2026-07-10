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
import java.util.Collections;
import java.util.List;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;

/**
 * Import strategy for the {@code consumes} section.
 */
public class ConsumesImportStrategy implements ImportStrategy<ClientSpec> {

    @Override
    public String sectionName() {
        return "consumes";
    }

    @Override
    public List<ClientSpec> readSection(IkanosSpec source) {
        // Standalone document: root-level consumes
        if (source.getConsumes() != null && !source.getConsumes().isEmpty()) {
            return source.getConsumes();
        }
        // Capability-wrapped form: capability.consumes
        if (source.getCapability() != null
                && source.getCapability().getConsumes() != null
                && !source.getCapability().getConsumes().isEmpty()) {
            return source.getCapability().getConsumes();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isImport(ClientSpec entry) {
        return entry instanceof ImportedConsumesHttpSpec;
    }

    @Override
    public ImportDirective toDirective(ClientSpec entry) {
        ImportedConsumesHttpSpec imp = (ImportedConsumesHttpSpec) entry;
        return new ImportDirective(imp.getFrom(), imp.getImportNamespace(),
                imp.getAlias(), imp.getDescription());
    }

    @Override
    public String getNamespace(ClientSpec entry) {
        return entry.getNamespace();
    }

    @Override
    public void setNamespace(ClientSpec entry, String namespace) {
        entry.setNamespace(namespace);
    }

    @Override
    public ClientSpec deepCopy(ClientSpec inline, SourceFileLoader loader) throws IOException {
        return loader.deepCopy(inline, HttpClientSpec.class);
    }
}
