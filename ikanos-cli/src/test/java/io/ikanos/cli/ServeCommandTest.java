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
import java.util.concurrent.atomic.AtomicReference;
import io.ikanos.bootstrap.CapabilityRuntime;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class ServeCommandTest {

    @Test
    public void callShouldUseDefaultPathWhenNoArgumentProvided() {
        AtomicReference<String> capturedFilePath = new AtomicReference<>();
        CapabilityRuntime runtime = new CapabilityRuntime() {
            @Override
            public int serve(String filePath) {
                capturedFilePath.set(filePath);
                return 0;
            }
        };

        int exitCode = new CommandLine(new ServeCommand(runtime)).execute();

        assertEquals(0, exitCode);
        assertEquals("ikanos.yaml", capturedFilePath.get());
    }
}