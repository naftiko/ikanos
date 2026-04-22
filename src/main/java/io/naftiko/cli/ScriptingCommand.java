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
package io.naftiko.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command that manages scripting governance on a running capability via the control port.
 * Without {@code --set}, displays current scripting configuration and execution stats. With
 * {@code --set key=value}, updates the specified fields at runtime via {@code PUT /scripting}.
 */
@Command(
    name = "scripting",
    mixinStandardHelpOptions = true,
    description = "Manage scripting governance on a running capability via the control port."
)
public class ScriptingCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mixin
    ControlPortMixin controlPort;

    @Option(names = "--set", description = "Update a scripting config field (e.g. --set enabled=true)",
            mapFallbackValue = "")
    Map<String, String> setFields;

    @Override
    public Integer call() {
        ControlPortClient client = new ControlPortClient(controlPort.baseUrl());

        try {
            if (setFields != null && !setFields.isEmpty()) {
                return updateScripting(client);
            }
            return showScripting(client);
        } catch (ControlPortClient.ControlPortUnreachableException e) {
            ControlPortMixin.printUnreachableError(controlPort.baseUrl());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int showScripting(ControlPortClient client)
            throws ControlPortClient.ControlPortUnreachableException, Exception {

        ControlPortClient.ControlPortResponse response = client.get("/scripting");
        if (response.statusCode() == 404) {
            System.err.println("Error: Scripting is not configured on this capability.");
            System.err.println("Hint: Add scripting to your control adapter:");
            System.err.println("  management:");
            System.err.println("    scripting:");
            System.err.println("      enabled: true");
            return 1;
        }
        if (response.statusCode() != 200) {
            System.err.println("Error: /scripting returned HTTP " + response.statusCode());
            return 1;
        }

        JsonNode root = MAPPER.readTree(response.body());

        boolean enabled = root.path("enabled").asBoolean();
        System.out.println("Scripting: " + (enabled ? "ENABLED" : "DISABLED"));

        // Config
        if (root.has("defaultLocation")) {
            System.out.println("  Location:  " + root.get("defaultLocation").asText());
        }
        if (root.has("defaultLanguage")) {
            System.out.println("  Language:  " + root.get("defaultLanguage").asText());
        }
        System.out.println("  Timeout:   " + root.path("timeout").asInt() + " ms");
        System.out.println("  Stmt Limit: " + root.path("statementLimit").asLong());
        if (root.has("allowedLanguages")) {
            System.out.println("  Allowed:   " + root.get("allowedLanguages"));
        }

        // Stats
        JsonNode stats = root.path("stats");
        if (!stats.isMissingNode()) {
            System.out.println("  Stats:");
            System.out.println("    Executions: " + stats.path("totalExecutions").asLong());
            System.out.println("    Errors:     " + stats.path("totalErrors").asLong());
            System.out.println("    Avg Duration: "
                    + String.format("%.2f", stats.path("averageDurationMs").asDouble()) + " ms");
            if (stats.has("lastExecutionAt")) {
                System.out.println("    Last Run:   " + stats.get("lastExecutionAt").asText());
            }
        }

        return 0;
    }

    private int updateScripting(ControlPortClient client)
            throws ControlPortClient.ControlPortUnreachableException, Exception {

        ObjectNode body = MAPPER.createObjectNode();
        for (Map.Entry<String, String> entry : setFields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            switch (key) {
                case "enabled" -> body.put(key, Boolean.parseBoolean(value));
                case "timeout" -> body.put(key, Integer.parseInt(value));
                case "statementLimit" -> body.put(key, Long.parseLong(value));
                case "defaultLocation", "defaultLanguage" -> body.put(key, value);
                case "allowedLanguages" -> {
                    ArrayNode arr = body.putArray(key);
                    for (String lang : value.split(",")) {
                        String trimmed = lang.trim();
                        if (!trimmed.isEmpty()) {
                            arr.add(trimmed);
                        }
                    }
                }
                default -> {
                    System.err.println("Error: Unknown scripting field: " + key);
                    return 1;
                }
            }
        }

        ControlPortClient.ControlPortResponse response =
                client.put("/scripting", MAPPER.writeValueAsString(body));

        if (response.statusCode() == 404) {
            System.err.println("Error: Scripting is not configured on this capability.");
            return 1;
        }
        if (response.statusCode() != 200) {
            System.err.println("Error: /scripting returned HTTP " + response.statusCode());
            return 1;
        }

        System.out.println("Scripting configuration updated.");
        return showScripting(client);
    }
}
