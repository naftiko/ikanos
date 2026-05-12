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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class CliTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream outCapture;

    @BeforeEach
    void setUp() {
        outCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void runShouldPrintUsageHint() {
        new Cli().run();

        assertTrue(outCapture.toString().contains("ikanos --help"));
    }

    @Test
    void versionProviderShouldReturnSchemaVersion() throws Exception {
        String[] version = new Cli.VersionProvider().getVersion();

        assertEquals(1, version.length);
        // Use the same source as the actual version provider to stay in sync
        assertEquals(io.ikanos.spec.util.VersionHelper.getSchemaVersion(), version[0]);
    }

    @Test
    void versionShouldNotBeEmpty() throws Exception {
        String[] version = new Cli.VersionProvider().getVersion();

        assertTrue(version[0] != null && !version[0].isEmpty(), "Version should not be empty");
    }

    @Test
    void versionShouldStartWithValidVersionPattern() throws Exception {
        String[] version = new Cli.VersionProvider().getVersion();

        assertTrue(version[0].matches("\\d+\\.\\d+\\.\\d+.*"), "Version should follow semantic versioning pattern");
    }

    @Test
    void commandLineShouldExitWithUsageErrorWhenCommandIsUnknown() {
        int exitCode = new CommandLine(new Cli()).execute("does-not-exist");

        assertEquals(2, exitCode);
    }
}
