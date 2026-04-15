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

@Command(
    name = "import",
    mixinStandardHelpOptions = true,
    description = "Import external specifications into Naftiko format.",
    subcommands = {ImportOpenApiCommand.class}
)
public class ImportCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'naftiko import --help' for available options");
    }
}
