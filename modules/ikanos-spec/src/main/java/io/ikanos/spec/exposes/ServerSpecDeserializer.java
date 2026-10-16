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
package io.ikanos.spec.exposes;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ValueDeserializer;

/**
 * Custom deserializer that dispatches to the correct {@link ServerSpec} subclass.
 *
 * <p>Discriminant rules (checked in order):</p>
 * <ol>
 *   <li>If the entry has a {@code from} field, it is an {@link ImportedExposesSpec} import.</li>
 *   <li>Otherwise, dispatch on the {@code type} field ({@code rest}, {@code mcp}, {@code skill},
 *       {@code control}) to the matching inline subclass.</li>
 * </ol>
 */
public class ServerSpecDeserializer extends ValueDeserializer<ServerSpec> {

    @Override
    public ServerSpec deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);

        // Import discriminant: presence of 'from' on a list entry.
        if (node.has("from")) {
            return ctxt.readTreeAsValue(node, ImportedExposesSpec.class);
        }

        String type = node.path("type").asString(null);

        return switch (type) {
            case "rest" -> ctxt.readTreeAsValue(node, RestServerSpec.class);
            case "mcp" -> ctxt.readTreeAsValue(node, McpServerSpec.class);
            case "skill" -> ctxt.readTreeAsValue(node, SkillServerSpec.class);
            case "control" -> ctxt.readTreeAsValue(node, ControlServerSpec.class);
            default -> throw DatabindException.from(p, "Unknown server spec type: '" + type + "'");
        };
    }
}
