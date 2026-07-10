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

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Custom deserializer that discriminates between an imported {@link ImportedAggregateSpec}
 * and an inline {@link AggregateSpec} based on the presence of the {@code from} field.
 *
 * <p>Design:</p>
 * <ul>
 *   <li>{@code from} present  → {@link ImportedAggregateSpec}</li>
 *   <li>{@code from} absent   → inline {@link AggregateSpec} via {@link InlineAggregateSpec}</li>
 * </ul>
 *
 * <p>{@link InlineAggregateSpec} is a thin subclass of {@link AggregateSpec} used solely to
 * bypass this custom deserializer when reading the inline form (matches the
 * {@code ClientSpec} / {@code HttpClientSpec} pattern). Part of Phase 1 of the unified import
 * mechanism — see {@code blueprints/unified-import-mechanism.md}.</p>
 */
public class AggregateSpecDeserializer extends JsonDeserializer<AggregateSpec> {

    @Override
    public AggregateSpec deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(p);

        if (node.has("from")) {
            return ctxt.readTreeAsValue(node, ImportedAggregateSpec.class);
        }

        return ctxt.readTreeAsValue(node, InlineAggregateSpec.class);
    }
}
