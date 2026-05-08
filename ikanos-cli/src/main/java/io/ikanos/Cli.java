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
package io.ikanos;

import io.ikanos.cli.CreateCommand;
import io.ikanos.cli.ExportCommand;
import io.ikanos.cli.HealthCommand;
import io.ikanos.cli.ImportCommand;
import io.ikanos.cli.MetricsCommand;
import io.ikanos.cli.ScriptingCommand;
import io.ikanos.cli.StatusCommand;
import io.ikanos.cli.TracesCommand;
import io.ikanos.cli.ValidateCommand;
import io.ikanos.spec.util.VersionHelper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
    name = "ikanos",
    mixinStandardHelpOptions = true,
    versionProvider = Cli.VersionProvider.class,
    description = "Ikanos CLI",
    subcommands = {CreateCommand.class, ValidateCommand.class, ImportCommand.class,
            ExportCommand.class, HealthCommand.class, StatusCommand.class,
            TracesCommand.class, MetricsCommand.class, ScriptingCommand.class}
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
        System.out.println("Use 'ikanos --help' for usage information");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Cli()).execute(args);
        System.exit(exitCode);
    }
}
