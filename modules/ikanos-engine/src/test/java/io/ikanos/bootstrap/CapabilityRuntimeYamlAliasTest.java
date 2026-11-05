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
package io.ikanos.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code ikanos serve} loads capabilities through {@link CapabilityRuntime#load}, not through
 * {@code Capability.Builder}: this test guards that path against YAML aliases (#923). The
 * document fails while it is read, so no adapter is started.
 */
class CapabilityRuntimeYamlAliasTest {

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void serveShouldFailOnAYamlAliasNamingItAndItsLocation() throws Exception {
        String file = Path.of(getClass().getResource("/yaml-aliases/alias-capability.yaml")
                .toURI()).toString();
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = new CapabilityRuntime().serve(file);
        } finally {
            System.setErr(originalErr);
        }

        String message = err.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode, message);
        assertTrue(message.contains("YAML alias '*list-inputs' is not supported"), message);
        assertTrue(message.contains("line: 36"), message);
    }
}
