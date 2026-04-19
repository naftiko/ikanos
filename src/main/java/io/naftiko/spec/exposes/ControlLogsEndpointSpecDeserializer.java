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
package io.naftiko.spec.exposes;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Deserializes the {@code logs} field in {@link ControlManagementSpec}. Accepts either a boolean or
 * an object:
 * <ul>
 * <li>{@code logs: true} → all log endpoints enabled with defaults</li>
 * <li>{@code logs: false} → all log endpoints disabled</li>
 * <li>{@code logs: {stream: true, max-subscribers: 3}} → advanced configuration</li>
 * </ul>
 */
public class ControlLogsEndpointSpecDeserializer extends JsonDeserializer<ControlLogsEndpointSpec> {

    @Override
    public ControlLogsEndpointSpec deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isBoolean()) {
            if (node.asBoolean()) {
                return ControlLogsEndpointSpec.allEnabled();
            }
            ControlLogsEndpointSpec spec = new ControlLogsEndpointSpec();
            spec.setLevelControl(false);
            spec.setStream(false);
            return spec;
        }

        ControlLogsEndpointSpec spec = new ControlLogsEndpointSpec();

        if (node.has("level-control")) {
            spec.setLevelControl(node.get("level-control").asBoolean());
        }

        if (node.has("stream")) {
            spec.setStream(node.get("stream").asBoolean());
        }

        if (node.has("max-subscribers")) {
            spec.setMaxSubscribers(node.get("max-subscribers").asInt());
        }

        return spec;
    }
}
