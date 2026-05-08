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

@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    aliases = {"c", "cr"},
    description = "Create a Ikanos resource. Look at the subcommands to know what kind of resource.",
    subcommands = {CreateCapabilityCommand.class}
)
public class CreateCommand implements Runnable {
    
    @Override
    public void run() {
        System.out.println("Use 'ikanos create --help' for available options");
    }
}
