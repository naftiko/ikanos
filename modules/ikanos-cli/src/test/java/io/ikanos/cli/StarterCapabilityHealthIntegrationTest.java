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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.ikanos.Cli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * End-to-end check of the getting-started flow (#758): a capability produced by
 * {@code ikanos create capability} must pass {@code ikanos health} without any manual edit and
 * without passing {@code --port}.
 */
public class StarterCapabilityHealthIntegrationTest {

    @Test
    public void healthShouldReportUpWhenServingFreshStarterCapability() throws Exception {
        assumeTrue(isPortFree(ControlPortMixin.DEFAULT_PORT),
                "Port " + ControlPortMixin.DEFAULT_PORT + " is busy on this machine");

        String capabilityName = "starter-" + UUID.randomUUID().toString().replace("-", "");
        Path yaml = Paths.get(capabilityName + ".ikanos.yaml");
        Thread runtimeThread = null;
        AtomicInteger serveExitCode = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            FileGenerator.generateCapabilityFile(capabilityName, FileFormat.YAML,
                    "https://api.example.com", String.valueOf(findFreePort()));

            runtimeThread = Thread.ofPlatform().start(() -> {
                try {
                    serveExitCode.set(new CommandLine(new ServeCommand()).execute(yaml.toString()));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });

            int exitCode = -1;
            String output = "";
            // Stop polling early if serve has already exited, so its failure is reported below
            for (int attempt = 0; attempt < 50 && exitCode != 0 && runtimeThread.isAlive();
                    attempt++) {
                ByteArrayOutputStream capture = new ByteArrayOutputStream();
                System.setOut(new PrintStream(capture, true));
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
                try {
                    exitCode = new CommandLine(new Cli()).execute("health");
                } finally {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                }
                output = capture.toString();
                if (exitCode != 0) {
                    Thread.sleep(100);
                }
            }

            boolean serveStillRunning = runtimeThread.isAlive();
            runtimeThread.interrupt();
            runtimeThread.join(Duration.ofSeconds(10).toMillis());

            assertNull(failure.get(), "Serve command should not fail");
            assertTrue(serveStillRunning,
                    "Serve exited before becoming healthy, with exit code " + serveExitCode.get());
            assertEquals(0, serveExitCode.get(), "Graceful shutdown should return exit code 0");
            assertEquals(0, exitCode, "ikanos health should succeed on a fresh starter capability");
            assertTrue(output.contains("Readiness: UP"), output);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (runtimeThread != null) {
                runtimeThread.interrupt();
                runtimeThread.join(Duration.ofSeconds(10).toMillis());
            }
            Files.deleteIfExists(yaml);
        }
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
