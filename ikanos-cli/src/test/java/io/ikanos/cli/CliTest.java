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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import io.ikanos.Cli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class CliTest {

    @Test
    public void executeShouldRouteServeSubcommand() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true));
            exitCode = new CommandLine(new Cli()).execute("serve", "missing.yaml");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("Error: File not found: missing.yaml"));
    }
}