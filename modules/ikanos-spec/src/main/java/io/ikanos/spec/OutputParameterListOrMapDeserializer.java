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
package io.ikanos.spec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson deserializer for {@code outputParameters} that accepts both forms:
 * <ul>
 *   <li><b>Array form</b> (legacy / Mapped / Mock / Consumed):
 *       {@code [{name: ..., type: ...}, ...]}</li>
 *   <li><b>Map form</b> (Orchestrated): {@code {param-name: {type: ...}, ...}} — the map key is
 *       injected as {@link OutputParameterSpec#setName(String)}.</li>
 * </ul>
 * Always returns {@code List<OutputParameterSpec>} to preserve backward compatibility with all
 * callers.
 */
public class OutputParameterListOrMapDeserializer extends JsonDeserializer<List<OutputParameterSpec>> {

    private final OutputParameterDeserializer itemDeserializer = new OutputParameterDeserializer();

    @Override
    public List<OutputParameterSpec> deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        List<OutputParameterSpec> result = new ArrayList<>();
        if (p.currentToken() == JsonToken.START_ARRAY) {
            // Legacy / Mapped / Mock / Consumed: array form
            while (p.nextToken() != JsonToken.END_ARRAY) {
                result.add(itemDeserializer.deserialize(p, ctxt));
            }
        } else if (p.currentToken() == JsonToken.START_OBJECT) {
            // Orchestrated / Consumed: map form — key becomes name
            JsonNode node = p.getCodec().readTree(p);
            node.properties().forEach(entry -> {
                try {
                    // Inject the map key as "name" into the node so that
                    // OutputParameterDeserializer can apply the value→mapping routing
                    // for ConsumedOutputParameter (value starts with '$').
                    JsonNode valueNode = entry.getValue();
                    if (valueNode.isObject() && !valueNode.has("name")) {
                        ((ObjectNode) valueNode).put("name", entry.getKey());
                    }
                    JsonParser itemParser = valueNode.traverse(p.getCodec());
                    itemParser.nextToken();
                    OutputParameterSpec spec = itemDeserializer.deserialize(itemParser, ctxt);
                    if (spec.getName() == null) {
                        spec.setName(entry.getKey());
                    }
                    result.add(spec);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Error deserializing outputParameter: " + entry.getKey(), e);
                }
            });
        }
        return result;
    }
}
