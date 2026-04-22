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
package io.naftiko.engine.exposes.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.naftiko.spec.exposes.ScriptingManagementSpec;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;

/**
 * Control Port resource for scripting governance.
 *
 * <p>{@code GET /scripting} returns the current scripting configuration and execution stats.
 * {@code PUT /scripting} updates configuration at runtime — takes effect on the next script
 * execution.</p>
 */
public class ScriptingResource extends ServerResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Get("json")
    public Representation getScripting() {
        ScriptingManagementSpec scripting =
                (ScriptingManagementSpec) getContext().getAttributes().get("scriptingSpec");

        if (scripting == null) {
            setStatus(Status.CLIENT_ERROR_NOT_FOUND);
            return new StringRepresentation(
                    "{\"error\":\"Scripting is not configured\"}", MediaType.APPLICATION_JSON);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("enabled", scripting.isEnabled());
        if (scripting.getDefaultLocation() != null) {
            root.put("defaultLocation", scripting.getDefaultLocation());
        }
        if (scripting.getDefaultLanguage() != null) {
            root.put("defaultLanguage", scripting.getDefaultLanguage());
        }
        root.put("timeout", scripting.getTimeout());
        root.put("statementLimit", scripting.getStatementLimit());
        if (!scripting.getAllowedLanguages().isEmpty()) {
            root.set("allowedLanguages",
                    MAPPER.valueToTree(scripting.getAllowedLanguages()));
        }

        ObjectNode stats = MAPPER.createObjectNode();
        stats.put("totalExecutions", scripting.getTotalExecutions());
        stats.put("totalErrors", scripting.getTotalErrors());
        stats.put("averageDurationMs",
                Math.round(scripting.getAverageDurationMs() * 100.0) / 100.0);
        if (scripting.getLastExecutionAt() != null) {
            stats.put("lastExecutionAt", scripting.getLastExecutionAt());
        }
        root.set("stats", stats);

        return new StringRepresentation(root.toString(), MediaType.APPLICATION_JSON);
    }

    @Put("json")
    public Representation putScripting(Representation entity) {
        ScriptingManagementSpec scripting =
                (ScriptingManagementSpec) getContext().getAttributes().get("scriptingSpec");

        if (scripting == null) {
            setStatus(Status.CLIENT_ERROR_NOT_FOUND);
            return new StringRepresentation(
                    "{\"error\":\"Scripting is not configured\"}", MediaType.APPLICATION_JSON);
        }

        try {
            String body = entity.getText();
            JsonNode update = MAPPER.readTree(body);

            if (update.has("enabled")) {
                scripting.setEnabled(update.get("enabled").asBoolean());
            }
            if (update.has("timeout")) {
                scripting.setTimeout(update.get("timeout").asInt());
            }
            if (update.has("statementLimit")) {
                scripting.setStatementLimit(update.get("statementLimit").asLong());
            }
            if (update.has("defaultLocation")) {
                scripting.setDefaultLocation(update.get("defaultLocation").asText());
            }
            if (update.has("defaultLanguage")) {
                scripting.setDefaultLanguage(update.get("defaultLanguage").asText());
            }
            if (update.has("allowedLanguages") && update.get("allowedLanguages").isArray()) {
                scripting.getAllowedLanguages().clear();
                for (JsonNode lang : update.get("allowedLanguages")) {
                    scripting.getAllowedLanguages().add(lang.asText());
                }
            }

            setStatus(Status.SUCCESS_OK);
            return getScripting();
        } catch (Exception e) {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return new StringRepresentation(
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}",
                    MediaType.APPLICATION_JSON);
        }
    }
}
