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

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI command that fetches Prometheus metrics from the control port. Connects to
 * {@code /metrics} and outputs Prometheus exposition format.
 */
@Command(
    name = "metrics",
    mixinStandardHelpOptions = true,
    description = "Fetch Prometheus metrics from the control port."
)
public class MetricsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCommand.class);

    @Mixin
    ControlPortMixin controlPort;

    @Option(names = "--filter", description = "Regex filter on metric names")
    String filter;

    @Override
    public Integer call() {
        ControlPortClient client = new ControlPortClient(controlPort.baseUrl());

        try {
            ControlPortClient.ControlPortResponse response = client.getPlain("/metrics");

            if (response.statusCode() == 503) {
                System.err.println("Metrics unavailable: OpenTelemetry is not active.");
                return 1;
            }

            String body = response.body();

            if (filter != null && !filter.isBlank()) {
                Pattern pattern = Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
                for (String line : body.split("\n")) {
                    if (pattern.matcher(line).find()) {
                        System.out.println(line);
                    }
                }
            } else {
                System.out.print(body);
            }

            return 0;

        } catch (ControlPortClient.ControlPortUnreachableException e) {
            ControlPortMixin.printUnreachableError(controlPort.baseUrl());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.debug("Metrics command failed", e);
            return 1;
        }
    }
}
