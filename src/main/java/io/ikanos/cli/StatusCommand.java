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
package io.ikanos.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI command that retrieves runtime status of a running capability via the control port. Connects
 * to {@code /status}.
 */
@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = "Retrieve runtime status of a running capability via the control port."
)
public class StatusCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(StatusCommand.class);

    @Mixin
    ControlPortMixin controlPort;

    @Override
    public Integer call() {
        ControlPortClient client = new ControlPortClient(controlPort.baseUrl());

        try {
            ControlPortClient.ControlPortResponse response = client.get("/status");
            if (response.statusCode() != 200) {
                System.err.println("Error: /status returned HTTP " + response.statusCode());
                return 1;
            }

            JsonNode root = MAPPER.readTree(response.body());

            // Derive overall status from adapter states
            boolean allStarted = true;
            JsonNode adapters = root.path("adapters");
            if (adapters.isArray()) {
                for (JsonNode adapter : adapters) {
                    if (!"started".equals(adapter.path("state").asText())) {
                        allStarted = false;
                        break;
                    }
                }
            }

            String label = root.path("capability").path("label").asText("unknown");
            System.out.println(label + ": " + (allStarted ? "UP" : "DEGRADED"));

            // Engine
            JsonNode engine = root.path("engine");
            System.out.println("  Engine:    " + engine.path("version").asText()
                    + " (Java " + engine.path("java").asText()
                    + ", native: " + engine.path("native").asBoolean() + ")");

            // Uptime
            String uptime = root.path("uptime").asText();
            System.out.println("  Uptime:    " + formatDuration(uptime));

            // OTel
            JsonNode otel = root.path("otel");
            String otelStatus = otel.path("status").asText("unknown");
            if ("active".equals(otelStatus) && otel.has("exporter")) {
                String exporter = otel.path("exporter").asText();
                String endpoint = otel.path("endpoint").asText("");
                System.out.println("  OTel:      active (" + exporter + " → " + endpoint + ")");
            } else {
                System.out.println("  OTel:      " + otelStatus);
            }

            // Adapters
            if (adapters.isArray() && !adapters.isEmpty()) {
                System.out.println("  Adapters:");
                for (JsonNode adapter : adapters) {
                    String type = adapter.path("type").asText();
                    int port = adapter.path("port").asInt();
                    String state = adapter.path("state").asText();
                    String ns = adapter.has("namespace")
                            ? adapter.path("namespace").asText() : type;
                    System.out.println("    " + ControlPortMixin.padRight(ns, 8)
                            + ":" + port + "   " + state);
                }
            }

            return 0;

        } catch (ControlPortClient.ControlPortUnreachableException e) {
            ControlPortMixin.printUnreachableError(controlPort.baseUrl());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.debug("Status command failed", e);
            return 1;
        }
    }

    static String formatDuration(String iso8601) {
        try {
            Duration d = Duration.parse(iso8601);
            long hours = d.toHours();
            long minutes = d.toMinutesPart();
            long seconds = d.toSecondsPart();
            if (hours > 0) {
                return hours + "h " + minutes + "m " + seconds + "s";
            } else if (minutes > 0) {
                return minutes + "m " + seconds + "s";
            } else {
                return seconds + "s";
            }
        } catch (Exception e) {
            logger.debug("Duration formatting failed for '{}': {}", iso8601, e.getMessage(), e);
            return iso8601;
        }
    }
}
