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

import picocli.CommandLine.Command;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.Callable;

import io.naftiko.cli.enums.FileFormat;

@Command(
    name = "capability",
    mixinStandardHelpOptions = true,
    aliases = {"cap"},
    description = "Create a new capability configuration file"
)
public class CreateCapabilityCommand implements Callable<Integer> {
    
    @Override
    public Integer call() {
        try (Scanner scanner = new Scanner(System.in)) {
            // Capability name.
            System.out.print("Type your capability name: ");
            String capabilityName = scanner.nextLine().trim();
            if (capabilityName.isEmpty()) {
                System.err.println("Error: capability name cannot be empty");
                return 1;
            }

            // Base URI.
            System.out.print("Type the targted URI: ");
            String baseUri = scanner.nextLine().trim();
            if (baseUri.isEmpty()) {
                System.err.println("Error: targetUri cannot be empty");
                return 1;
            }

            // Port.
            System.out.print("Type your capability exposition port: ");
            String port = scanner.nextLine().trim();
            if (port.isEmpty()) {
                System.err.println("Error: port cannot be empty");
                return 1;
            }
            
            System.out.println("Creating capability: " + capabilityName + " " + FileFormat.YAML + " " + baseUri + " " + port);
            FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML, baseUri, port);
            
            return 0;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

}
