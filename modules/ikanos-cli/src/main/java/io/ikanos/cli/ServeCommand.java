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

import java.util.concurrent.Callable;
import io.ikanos.bootstrap.CapabilityRuntime;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
    name = "serve",
    mixinStandardHelpOptions = true,
    aliases = {"s"},
    description = "Run a YAML capability and block until the process is stopped"
)
public class ServeCommand implements Callable<Integer> {

    private final CapabilityRuntime runtime;

    @Parameters(index = "0", arity = "0..1",
        description = "Path to the YAML capability configuration file to run",
        defaultValue = "ikanos.yaml")
    private String filePath;

    public ServeCommand() {
        this(new CapabilityRuntime());
    }

    ServeCommand(CapabilityRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        return runtime.serve(filePath);
    }
}
