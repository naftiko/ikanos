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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.ikanos.spec.util.IkanosMappers;

/**
 * CLI command that checks health status of a running capability via the control port. Connects to
 * {@code /health/live} and {@code /health/ready}.
 */
@Command(
    name = "health",
    mixinStandardHelpOptions = true,
    description = "Check health status of a running capability via the control port."
)
public class HealthCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = IkanosMappers.json();
    private static final Logger logger = LoggerFactory.getLogger(HealthCommand.class);

    @Mixin
    ControlPortMixin controlPort;

    @Override
    public Integer call() {
        ControlPortClient client = new ControlPortClient(controlPort.baseUrl());

        try {
            ControlPortClient.ControlPortResponse liveResponse = client.get("/health/live");
            JsonNode liveNode = MAPPER.readTree(liveResponse.body());
            String liveStatus = liveNode.path("status").asString("UNKNOWN");

            ControlPortClient.ControlPortResponse readyResponse = client.get("/health/ready");
            JsonNode readyNode = MAPPER.readTree(readyResponse.body());
            String readyStatus = readyNode.path("status").asString("UNKNOWN");

            System.out.println("Liveness:  " + liveStatus);

            JsonNode adapters = readyNode.path("adapters");
            if (adapters.isArray()) {
                long started = 0;
                long total = 0;
                for (JsonNode adapter : adapters) {
                    total++;
                    if ("started".equals(adapter.path("state").asString())) {
                        started++;
                    }
                }
                System.out.println("Readiness: " + readyStatus
                        + " (" + started + "/" + total + " adapters started)");

                if (started < total) {
                    for (JsonNode adapter : adapters) {
                        String type = adapter.path("type").asString();
                        int port = adapter.path("port").asInt();
                        String state = adapter.path("state").asString();
                        String line = "  " + ControlPortMixin.padRight(type, 8)
                                + ":" + port + "  " + state;
                        if (adapter.has("reason")) {
                            line += " — " + adapter.path("reason").asString();
                        }
                        System.out.println(line);
                    }
                }
            } else {
                System.out.println("Readiness: " + readyStatus);
            }

            return "UP".equals(readyStatus) ? 0 : 1;

        } catch (ControlPortClient.ControlPortUnreachableException e) {
            ControlPortMixin.printUnreachableError(controlPort.baseUrl());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.debug("Health command failed", e);
            return 1;
        }
    }
}
