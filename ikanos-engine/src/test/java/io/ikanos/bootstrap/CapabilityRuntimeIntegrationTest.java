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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.engine.observability.TelemetryBootstrap;

public class CapabilityRuntimeIntegrationTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path tempDir;

    @Test
    public void serveShouldStopControlServerWhenRuntimeThreadIsInterrupted() throws Exception {
        int port = findFreePort();
        Path capabilityFile = createCapabilityFile(port);
        CapabilityRuntime runtime = new CapabilityRuntime();
        HttpClient httpClient = HttpClient.newHttpClient();
        AtomicInteger exitCode = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread runtimeThread = Thread.ofPlatform().start(() -> {
            try {
                exitCode.set(runtime.serve(capabilityFile.toString()));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        waitUntilReady(httpClient, port);

        runtimeThread.interrupt();
        runtimeThread.join(Duration.ofSeconds(10).toMillis());

        assertFalse(runtimeThread.isAlive(), "Runtime thread should exit after shutdown");
        assertNull(failure.get(), "Runtime thread should not fail");
        assertEquals(0, exitCode.get(), "Graceful shutdown should return exit code 0");

        waitUntilStopped(httpClient, port);
    }

    private Path createCapabilityFile(int port) throws IOException {
        Path source = Path.of("src", "test", "resources", "control", "control-capability.yaml");
        String yaml = Files.readString(source).replace("port: 9199", "port: " + port);
        Path target = tempDir.resolve("control-capability.yaml");
        Files.writeString(target, yaml);
        return target;
    }

    private void waitUntilReady(HttpClient httpClient, int port) throws Exception {
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(readyRequest(port),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    assertTrue(response.body().contains("\"status\":\"UP\""));
                    return;
                }
                lastFailure = new IllegalStateException("Unexpected status: " + response.statusCode());
            } catch (IOException e) {
                lastFailure = e;
            }
            Thread.sleep(100);
        }

        assertNotNull(lastFailure, "Server should become ready or expose the last failure");
        throw new AssertionError("Control server did not become ready", lastFailure);
    }

    private void waitUntilStopped(HttpClient httpClient, int port) throws Exception {
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(readyRequest(port),
                        HttpResponse.BodyHandlers.ofString());
                lastFailure = new IllegalStateException("Server still responding with status "
                        + response.statusCode());
            } catch (ConnectException e) {
                return;
            } catch (IOException e) {
                throw new AssertionError("Unexpected I/O error while polling for shutdown", e);
            }
            Thread.sleep(100);
        }

        throw new AssertionError("Control server did not stop", lastFailure);
    }

    private HttpRequest readyRequest(int port) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health/ready"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    private int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
    }
}
