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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.util.ImportedBindingSpec;

/**
 * Import strategy for the {@code binds} section.
 */
public class BindsImportStrategy implements ImportStrategy<BindingSpec> {

    private final ObjectMapper mapper;

    public BindsImportStrategy() {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String sectionName() {
        return "binds";
    }

    @Override
    public List<BindingSpec> readSection(IkanosSpec source) {
        // Standalone document: root-level binds
        if (source.getBinds() != null && !source.getBinds().isEmpty()) {
            return source.getBinds();
        }
        // Capability-wrapped form: capability.binds
        // Note: binds is at the IkanosSpec root level, not under capability, but some
        // documents may wrap it. Check capability.binds as fallback.
        if (source.getCapability() != null
                && source.getCapability().getBinds() != null
                && !source.getCapability().getBinds().isEmpty()) {
            return source.getCapability().getBinds();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isImport(BindingSpec entry) {
        return entry instanceof ImportedBindingSpec;
    }

    @Override
    public ImportDirective toDirective(BindingSpec entry) {
        ImportedBindingSpec imp = (ImportedBindingSpec) entry;
        return new ImportDirective(imp.getFrom(), imp.getImportNamespace(),
                imp.getAlias(), imp.getDescription());
    }

    @Override
    public String getNamespace(BindingSpec entry) {
        return entry.getNamespace();
    }

    @Override
    public void setNamespace(BindingSpec entry, String namespace) {
        entry.setNamespace(namespace);
    }

    @Override
    public BindingSpec deepCopy(BindingSpec inline) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(inline);
        return mapper.readValue(bytes, BindingSpec.class);
    }
}
