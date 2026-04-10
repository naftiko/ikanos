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
package io.naftiko;

import io.naftiko.cli.CreateCommand;
import io.naftiko.cli.ValidateCommand;
import io.naftiko.util.VersionHelper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
    name = "naftiko",
    mixinStandardHelpOptions = true,
    versionProvider = Cli.VersionProvider.class,
    description = "Naftiko CLI",
    subcommands = {CreateCommand.class, ValidateCommand.class}
)
public class Cli implements Runnable {

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { VersionHelper.getSchemaVersion() };
        }
    }

    @Override
    public void run() {
        System.out.println("Use 'naftiko --help' for usage information");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Cli()).execute(args);
        System.exit(exitCode);
    }
}
