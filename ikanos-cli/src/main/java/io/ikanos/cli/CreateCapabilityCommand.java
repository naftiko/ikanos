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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(
    name = "capability",
    mixinStandardHelpOptions = true,
    aliases = {"cap"},
    description = "Create a new capability configuration file"
)
public class CreateCapabilityCommand implements Callable<Integer> {

    InputStream input = System.in;
    PrintStream out = System.out;
    PrintStream err = System.err;

    void generateCapabilityFile(String capabilityName, String baseUri, String port)
            throws IOException {
        FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML, baseUri, port);
    }
    
    @Override
    public Integer call() {
        try (Scanner scanner = new Scanner(input)) {
            // Capability name.
            out.print("Type your capability name: ");
            String capabilityName = scanner.nextLine().trim();
            if (capabilityName.isEmpty()) {
                err.println("Error: capability name cannot be empty");
                return 1;
            }

            // Base URI.
            out.print("Type the targted URI: ");
            String baseUri = scanner.nextLine().trim();
            if (baseUri.isEmpty()) {
                err.println("Error: targetUri cannot be empty");
                return 1;
            }

            // Port.
            out.print("Type your capability exposition port: ");
            String port = scanner.nextLine().trim();
            if (port.isEmpty()) {
                err.println("Error: port cannot be empty");
                return 1;
            }
            
            out.println("Creating capability: " + capabilityName + " " + FileFormat.YAML + " " + baseUri + " " + port);
            generateCapabilityFile(capabilityName, baseUri, port);
            
            return 0;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

}
