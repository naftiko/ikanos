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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import io.ikanos.Cli;

public class MetricsCommandTest {

    private HttpServer server;
    private int port;
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        outCapture = new ByteArrayOutputStream();
        errCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outCapture));
        System.setErr(new PrintStream(errCapture));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        server.stop(0);
    }

    @Test
    void metricsShouldDisplayPrometheusOutput() {
        String metrics = "# HELP IKANOS_up Capability liveness indicator\n"
                + "# TYPE IKANOS_up gauge\n"
                + "IKANOS_up 1\n";

        server.createContext("/metrics", exchange -> {
            byte[] body = metrics.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("metrics", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("IKANOS_up"));
        assertTrue(output.contains("# HELP"));
    }

    @Test
    void metricsShouldFilterByRegex() {
        String metrics = "# HELP IKANOS_up Capability liveness indicator\n"
                + "# TYPE IKANOS_up gauge\n"
                + "IKANOS_up 1\n"
                + "# HELP http_requests_total Total HTTP requests\n"
                + "# TYPE http_requests_total counter\n"
                + "http_requests_total 42\n";

        server.createContext("/metrics", exchange -> {
            byte[] body = metrics.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("metrics", "--port", String.valueOf(port),
                "--filter", "ikanos");

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("IKANOS_up"));
        assertFalse(output.contains("http_requests_total"));
    }

    @Test
    void metricsShouldReturnOneWhenOtelInactive() {
        String errorJson = "{\"error\":\"OpenTelemetry is not active.\"}";

        server.createContext("/metrics", exchange -> {
            byte[] body = errorJson.getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("metrics", "--port", String.valueOf(port));

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("OpenTelemetry is not active"));
    }

    @Test
    void metricsShouldReturnOneWhenUnreachable() {
        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("metrics", "--port", "1");

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Cannot connect to control port"));
    }

    @Test
    void metricsShouldReturnOneWhenFilterRegexIsInvalid() {
        String metrics = "IKANOS_up 1\n";
        server.createContext("/metrics", exchange -> {
            byte[] body = metrics.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("metrics", "--port", String.valueOf(port), "--filter", "[");

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Error:"));
    }
}
