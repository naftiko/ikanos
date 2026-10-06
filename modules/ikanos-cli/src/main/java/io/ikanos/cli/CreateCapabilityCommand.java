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
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(
    name = "capability",
    mixinStandardHelpOptions = true,
    aliases = {"cap"},
    description = "Create a new capability configuration file. Values not given as options are "
        + "asked for interactively."
)
public class CreateCapabilityCommand implements Callable<Integer> {

    @Option(names = {"-n", "--name"}, paramLabel = "<name>",
            description = "Capability name (the file is <name>.ikanos.yaml)")
    String capabilityName;

    @Option(names = {"-u", "--target-uri"}, paramLabel = "<uri>",
            description = "Base URI of the API the capability consumes")
    String targetUri;

    @Option(names = {"-p", "--port"}, paramLabel = "<port>",
            description = "Port to expose the capability on")
    String port;

    // package-private for testing — allows injection of custom input/output streams
    InputStream input = System.in;
    PrintStream out = System.out;
    PrintStream err = System.err;

    // package-private for testing — allows overriding file generation logic
    void generateCapabilityFile(String capabilityName, String baseUri, String port)
            throws IOException {
        FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML, baseUri, port);
    }
    
    @Override
    public Integer call() {
        try (Scanner scanner = new Scanner(input)) {
            // Capability name.
            String capabilityName = valueOrAsk(this.capabilityName, "Type your capability name: ",
                    scanner);
            if (capabilityName.isEmpty()) {
                err.println("Error: capability name cannot be empty");
                return 1;
            }

            // Base URI.
            String baseUri = valueOrAsk(this.targetUri, "Enter the target URI: ", scanner);
            if (baseUri.isEmpty()) {
                err.println("Error: targetUri cannot be empty");
                return 1;
            }

            // Port.
            String port = valueOrAsk(this.port, "Enter the port to expose your capability on: ",
                    scanner);
            if (port.isEmpty()) {
                err.println("Error: port cannot be empty");
                return 1;
            }

            out.println("Creating capability: " + capabilityName + " " + FileFormat.YAML + " " + baseUri + " " + port);
            generateCapabilityFile(capabilityName, baseUri, port);

            return 0;
        } catch (NoSuchElementException e) {
            // A value was neither given as an option nor answered (e.g. stdin closed in a script).
            err.println("Error: missing value. To create a capability without prompts, also pass "
                    + String.join(", ", missingOptions()) + ".");
            return 1;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Returns the option value when it was given on the command line; otherwise asks for it, so
     * only the missing values are prompted.
     */
    String valueOrAsk(String optionValue, String prompt, Scanner scanner) {
        if (optionValue != null) {
            return optionValue.trim();
        }
        out.print(prompt);
        return scanner.nextLine().trim();
    }

    /**
     * Returns the options that were not given on the command line, so the error hint names only
     * what is still needed to run without prompts.
     */
    List<String> missingOptions() {
        List<String> missing = new ArrayList<>();
        if (capabilityName == null) {
            missing.add("--name");
        }
        if (targetUri == null) {
            missing.add("--target-uri");
        }
        if (port == null) {
            missing.add("--port");
        }
        return missing;
    }

}