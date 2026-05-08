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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.exposes.ServerSpec;
import picocli.CommandLine.Option;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared options for CLI commands that connect to a running control port. Discovery cascade:
 * explicit flag → environment variable → local YAML parse → default.
 */
public class ControlPortMixin {

    static final int DEFAULT_PORT = 9090;
    static final String DEFAULT_ADDRESS = "localhost";

    @Option(names = "--port", description = "Control port to connect to (default: 9090)")
    Integer port;

    @Option(names = "--address", description = "Control port address (default: localhost)")
    String address;

    String resolveAddress() {
        if (address != null) {
            return address;
        }
        String env = System.getenv("IKANOS_CONTROL_ADDRESS");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_ADDRESS;
    }

    int resolvePort() {
        if (port != null) {
            return port;
        }
        String env = System.getenv("IKANOS_CONTROL_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to YAML discovery
            }
        }
        int yamlPort = discoverPortFromYaml();
        if (yamlPort > 0) {
            return yamlPort;
        }
        return DEFAULT_PORT;
    }

    String baseUrl() {
        return "http://" + resolveAddress() + ":" + resolvePort();
    }

    int discoverPortFromYaml() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cwd, "*.{yml,yaml}")) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            for (Path path : stream) {
                try {
                    IkanosSpec spec = mapper.readValue(path.toFile(), IkanosSpec.class);
                    if (spec.getCapability() != null && spec.getCapability().getExposes() != null) {
                        for (ServerSpec server : spec.getCapability().getExposes()) {
                            if (server instanceof ControlServerSpec control && control.getPort() > 0) {
                                return control.getPort();
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Not a valid Ikanos YAML — skip
                }
            }
        } catch (IOException ignored) {
            // CWD not readable — fall through
        }
        return -1;
    }

    static String padRight(String text, int length) {
        if (text.length() >= length) {
            return text;
        }
        return text + " ".repeat(length - text.length());
    }

    static void printUnreachableError(String baseUrl) {
        System.err.println("Error: Cannot connect to control port at " + baseUrl);
        System.err.println("Hint: Ensure your capability is running with a control adapter:");
        System.err.println("  exposes:");
        System.err.println("    - type: control");
        System.err.println("      port: 9090");
    }
}
