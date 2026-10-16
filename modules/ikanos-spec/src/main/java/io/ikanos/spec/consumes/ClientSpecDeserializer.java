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
package io.ikanos.spec.consumes;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ValueDeserializer;

/**
 * Custom deserializer for {@code consumes} entries.
 *
 * <p>Design:</p>
 * <ul>
 *   <li>{@code from} present → {@link ImportedConsumesHttpSpec}</li>
 *   <li>otherwise, dispatch on {@code type} through {@link ClientSpecTypes}: absent or
 *       {@code http} → {@link HttpClientSpec}; a registered type → its spec class; anything else
 *       → a clear error naming the type and the registered ones.</li>
 * </ul>
 *
 * <p>The legacy {@code location} keyword is rejected with a migration-guidance error.
 * This is part of Phase 1 of the unified import mechanism — see
 * {@code blueprints/unified-import-mechanism.md}.</p>
 */
public class ClientSpecDeserializer extends ValueDeserializer<ClientSpec> {

    @Override
    public ClientSpec deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);

        // Reject the legacy 'location' keyword on import entries with a clear migration message.
        // Heuristic: an import entry is one that has 'import' (the namespace to import) and no
        // 'baseUri' (which is mandatory on an inline HttpClientSpec). We avoid rejecting valid
        // inline adapter specs that happen to use 'location' as a parameter name elsewhere.
        if (node.has("location") && node.has("import") && !node.has("baseUri")) {
            throw DatabindException.from(p, "Import keyword 'location' is no longer supported on 'consumes' entries; "
                    + "use 'from' instead. See blueprints/unified-import-mechanism.md.");
        }

        // If 'from' field is present -> ImportedConsumesHttpSpec
        if (node.has("from")) {
            return ctxt.readTreeAsValue(node, ImportedConsumesHttpSpec.class);
        }

        // Otherwise dispatch on 'type' (absent -> http)
        JsonNode typeNode = node.get("type");
        String type = typeNode != null && !typeNode.isNull() ? typeNode.asString() : null;
        Class<? extends ClientSpec> specClass = ClientSpecTypes.resolve(type);
        if (specClass == null) {
            throw DatabindException.from(p, "Unknown consumes type '" + type + "'. Registered types: "
                    + ClientSpecTypes.registeredTypes());
        }
        return ctxt.readTreeAsValue(node, specClass);
    }
}
