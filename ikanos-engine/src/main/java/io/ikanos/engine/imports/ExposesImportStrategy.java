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
import io.ikanos.spec.exposes.ImportedExposesSpec;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Import strategy for the {@code exposes} section.
 */
public class ExposesImportStrategy implements ImportStrategy<ServerSpec> {

    private final ObjectMapper mapper;

    public ExposesImportStrategy() {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String sectionName() {
        return "exposes";
    }

    @Override
    public List<ServerSpec> readSection(IkanosSpec source) {
        // Standalone document: root-level exposes
        if (source.getExposes() != null && !source.getExposes().isEmpty()) {
            return source.getExposes();
        }
        // Capability-wrapped form: capability.exposes
        if (source.getCapability() != null
                && source.getCapability().getExposes() != null
                && !source.getCapability().getExposes().isEmpty()) {
            return source.getCapability().getExposes();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isImport(ServerSpec entry) {
        return entry instanceof ImportedExposesSpec;
    }

    @Override
    public ImportDirective toDirective(ServerSpec entry) {
        ImportedExposesSpec imp = (ImportedExposesSpec) entry;
        return new ImportDirective(imp.getFrom(), imp.getImportNamespace(),
                imp.getAlias(), imp.getDescription());
    }

    @Override
    public String getNamespace(ServerSpec entry) {
        return entry.getNamespace();
    }

    @Override
    public void setNamespace(ServerSpec entry, String namespace) {
        entry.setNamespace(namespace);
    }

    @Override
    public ServerSpec deepCopy(ServerSpec inline) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(inline);
        return mapper.readValue(bytes, ServerSpec.class);
    }
}
