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
package io.naftiko.spec.consumes;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Custom deserializer that discriminates between Import and regular HttpClientSpec
 * based on presence of 'location' field.
 * 
 * Design:
 * - If 'location' field is present -> ImportedConsumesHttpSpec
 * - Otherwise -> HttpClientSpec
 */
public class ClientSpecDeserializer extends JsonDeserializer<ClientSpec> {

    @Override
    public ClientSpec deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(p);

        // If 'location' field is present -> ImportedConsumesHttpSpec
        if (node.has("location")) {
            return ctxt.readTreeAsValue(node, ImportedConsumesHttpSpec.class);
        }

        // Otherwise -> HttpClientSpec
        return ctxt.readTreeAsValue(node, HttpClientSpec.class);
    }
}
