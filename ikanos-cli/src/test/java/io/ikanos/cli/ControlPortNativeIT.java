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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end NATIVE-level coverage for the Control Port (feature #14, tracking issue #578).
 *
 * <p>Unlike {@code ServeCommandTest} / {@code CapabilityRuntimeIntegrationTest}, which boot
 * {@code serve} in-process on a JVM thread, this test spawns the <strong>native CLI binary</strong>
 * ({@code ikanos-cli/target/ikanos-cli[.exe]}) as a real subprocess, runs {@code ikanos serve} on
 * the native-regression fixture, and drives the control endpoints over HTTP. It therefore validates
 * the full packaging path (GraalVM native image + CLI launch), not just the engine feature.
 *
 * <p><strong>Opt-in by design.</strong> The native binary is only produced under the {@code -Pnative}
 * profile (in CI, or locally via {@code mvn -Pnative -pl ikanos-cli}); it is absent from an ordinary
 * {@code mvn test} run. The test is therefore <em>skipped</em> (JUnit assumption) unless the caller
 * points it at a built binary with {@code -Dikanos.native.bin=<path>}. This mirrors the established
 * pattern in {@code IkanosPolychroRulesetTest} (skip when an external tool is unavailable) and keeps
 * the test out of the standard build without introducing a failsafe/{@code @Tag} layer the repo does
 * not otherwise use.
 *
 * <p>To run it:
 * <pre>{@code
 *   mvn -q -pl ikanos-cli -Pnative package -DskipTests          # build the native binary
 *   mvn -q -pl ikanos-cli test -Dtest=ControlPortNativeIT \
 *       -Dikanos.native.bin=ikanos-cli/target/ikanos-cli        # run this test against it
 * }</pre>
 */
@DisplayName("Control Port — native end-to-end (#14)")
public class ControlPortNativeIT {

    /** System property carrying the path to the built native CLI binary. */
    private static final String NATIVE_BIN_PROPERTY = "ikanos.native.bin";

    /**
     * Control port declared in {@code serve-http.ikanos.yaml}. The fixture pins it (it is not a
     * free port), so only one instance of this test may run against the binary at a time.
     */
    private static final int CONTROL_PORT = 9619;

    private static final Path FIXTURE =
            Path.of("src", "test", "resources", "native-regression", "serve-http.ikanos.yaml");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STARTUP_BUDGET = Duration.ofSeconds(30);

    private HttpClient httpClient;
    private Process serveProcess;
    private Thread stdoutDrain;
    private final AtomicReference<String> processOutput = new AtomicReference<>("");

    @BeforeEach
    void startNativeServe() throws Exception {
        String binProperty = System.getProperty(NATIVE_BIN_PROPERTY);
        assumeTrue(binProperty != null && !binProperty.isBlank(),
                "Skipping: native binary not provided (set -D" + NATIVE_BIN_PROPERTY + "=<path>).");

        Path binary = Path.of(binProperty).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(binary),
                "Skipping: native binary not found at " + binary);
        assumeTrue(Files.isReadable(FIXTURE),
                "Skipping: native fixture not found at " + FIXTURE.toAbsolutePath());

        httpClient = HttpClient.newHttpClient();

        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "serve", FIXTURE.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        serveProcess = builder.start();

        // Drain stdout on a dedicated thread to avoid the subprocess blocking on a full pipe buffer.
        stdoutDrain = Thread.ofPlatform().daemon().start(() -> {
            try (InputStream in = serveProcess.getInputStream()) {
                processOutput.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Stream closed on teardown — expected.
            }
        });

        waitUntilLive();
    }

    @AfterEach
    void stopNativeServe() throws InterruptedException {
        if (serveProcess != null && serveProcess.isAlive()) {
            serveProcess.destroy();
            if (!serveProcess.waitFor(10, TimeUnit.SECONDS)) {
                serveProcess.destroyForcibly();
            }
        }
        if (stdoutDrain != null) {
            stdoutDrain.join(Duration.ofSeconds(2).toMillis());
        }
    }

    @Test
    @DisplayName("/health/live answers 200 UP from the native binary")
    void healthLiveShouldReturnUpFromNativeBinary() throws Exception {
        HttpResponse<String> response = get("/health/live");

        assertEquals(200, response.statusCode(), diagnostics("/health/live"));
        assertTrue(response.body().contains("\"status\":\"UP\""),
                "Expected liveness body to report UP, was: " + response.body());
    }

    @Test
    @DisplayName("/health/ready answers a liveness verdict (200 UP or 503 DEGRADED)")
    void healthReadyShouldReturnReadinessVerdict() throws Exception {
        HttpResponse<String> response = get("/health/ready");

        assertTrue(response.statusCode() == 200 || response.statusCode() == 503,
                "Readiness must be 200 (UP) or 503 (DEGRADED), was: " + diagnostics("/health/ready"));
    }

    @Test
    @DisplayName("/status answers 200 (info management is enabled in the fixture)")
    void statusShouldReturnInfoWhenManagementInfoEnabled() throws Exception {
        HttpResponse<String> response = get("/status");

        assertEquals(200, response.statusCode(), diagnostics("/status"));
    }

    @Test
    @DisplayName("/metrics and /traces are reachable (503 expected without OTel active)")
    void observabilityEndpointsShouldBeReachable() throws Exception {
        // The fixture enables no OpenTelemetry exporter, so the control adapter answers these with
        // 503. We assert reachability + the documented degraded status, not a 200 payload.
        HttpResponse<String> metrics = get("/metrics");
        HttpResponse<String> traces = get("/traces");

        assertEquals(503, metrics.statusCode(),
                "Without OTel active, /metrics is expected to be 503: " + diagnostics("/metrics"));
        assertEquals(503, traces.statusCode(),
                "Without OTel active, /traces is expected to be 503: " + diagnostics("/traces"));
    }

    /** Polls {@code /health/live} (always 200 once the adapter is up) until the binary is ready. */
    private void waitUntilLive() throws Exception {
        long deadline = System.nanoTime() + STARTUP_BUDGET.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!serveProcess.isAlive()) {
                throw new AssertionError("Native serve process exited before becoming ready.\n"
                        + diagnostics("/health/live"));
            }
            try {
                HttpResponse<String> response = get("/health/live");
                if (response.statusCode() == 200) {
                    return;
                }
                lastFailure = new IllegalStateException("Unexpected status: " + response.statusCode());
            } catch (ConnectException e) {
                lastFailure = e; // Server not listening yet.
            } catch (IOException e) {
                lastFailure = e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Native control server did not become ready within "
                + STARTUP_BUDGET.toSeconds() + "s.\n" + diagnostics("/health/live"), lastFailure);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + CONTROL_PORT + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Builds a failure message that includes the captured subprocess output for triage. */
    private String diagnostics(String path) {
        return "GET " + path + " on control port " + CONTROL_PORT
                + " (native subprocess alive=" + (serveProcess != null && serveProcess.isAlive())
                + ").\n--- native serve output ---\n" + processOutput.get();
    }
}
