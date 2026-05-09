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

public class StatusCommandTest {

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
    void statusShouldDisplayCapabilityInfo() {
        String json = """
                {"capability":{"label":"Weather Service","specVersion":"1.0.0-alpha2"},
                 "engine":{"version":"1.0.0-alpha2","java":"21.0.3","native":false},
                 "uptime":"PT2H34M12S",
                 "otel":{"status":"inactive"},
                 "adapters":[
                   {"type":"mcp","namespace":"weather","port":3000,"state":"started"},
                   {"type":"control","port":9090,"state":"started"}
                 ]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Weather Service: UP"));
        assertTrue(output.contains("Engine:"));
        assertTrue(output.contains("21.0.3"));
        assertTrue(output.contains("Uptime:"));
        assertTrue(output.contains("2h 34m 12s"));
        assertTrue(output.contains("OTel:      inactive"));
        assertTrue(output.contains("weather"));
    }

    @Test
    void statusShouldShowDegradedWhenAdapterStopped() {
        String json = """
                {"capability":{"label":"Test"},
                 "engine":{"version":"1.0.0","java":"21","native":false},
                 "uptime":"PT5S",
                 "otel":{"status":"inactive"},
                 "adapters":[
                   {"type":"mcp","namespace":"weather","port":3000,"state":"stopped"},
                   {"type":"control","port":9090,"state":"started"}
                 ]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("DEGRADED"));
    }

    @Test
    void statusShouldReturnOneWhenUnreachable() {
        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", "1");

        assertEquals(1, exitCode);
        String errOutput = errCapture.toString();
        assertTrue(errOutput.contains("Cannot connect to control port"));
    }

    @Test
    void statusShouldReturnOneWhenEndpointReturnsNon200() {
        server.createContext("/status", exchange -> {
            byte[] body = "{\"error\":\"boom\"}".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("/status returned HTTP 500"));
    }

    @Test
    void statusShouldDisplayActiveOtelExporterWhenPresent() {
        String json = """
                {"capability":{"label":"Weather Service"},
                 "engine":{"version":"1.0.0","java":"21","native":false},
                 "uptime":"PT15S",
                 "otel":{"status":"active","exporter":"otlp","endpoint":"http://otel:4317"},
                 "adapters":[]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("active (otlp \u2192 http://otel:4317)"));
    }

    @Test
    void formatDurationShouldFormatHoursMinutesSeconds() {
        assertEquals("2h 34m 12s", StatusCommand.formatDuration("PT2H34M12S"));
    }

    @Test
    void formatDurationShouldFormatMinutesOnly() {
        assertEquals("5m 30s", StatusCommand.formatDuration("PT5M30S"));
    }

    @Test
    void formatDurationShouldFormatSecondsOnly() {
        assertEquals("45s", StatusCommand.formatDuration("PT45S"));
    }

    @Test
    void formatDurationShouldReturnRawStringOnInvalid() {
        assertEquals("invalid", StatusCommand.formatDuration("invalid"));
    }

    @Test
    void statusShouldDisplayNativeEngineIndicator() {
        String json = """
                {"capability":{"label":"Native App"},
                 "engine":{"version":"1.0.0","java":"21","native":true},
                 "uptime":"PT1S",
                 "otel":{"status":"inactive"},
                 "adapters":[]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("native"));
    }

    @Test
    void statusShouldHandleOtelInactiveWithoutExporter() {
        String json = """
                {"capability":{"label":"Test"},
                 "engine":{"version":"1.0.0","java":"21","native":false},
                 "uptime":"PT10S",
                 "otel":{"status":"inactive"},
                 "adapters":[]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("inactive"));
    }

    @Test
    void statusShouldDisplayMultipleAdapters() {
        String json = """
                {"capability":{"label":"Multi-adapter"},
                 "engine":{"version":"1.0.0","java":"21","native":false},
                 "uptime":"PT20S",
                 "otel":{"status":"inactive"},
                 "adapters":[
                   {"type":"rest","port":8080,"state":"started"},
                   {"type":"mcp","namespace":"ns1","port":3000,"state":"started"},
                   {"type":"skill","namespace":"ns2","port":4000,"state":"started"},
                   {"type":"control","port":9090,"state":"started"}
                 ]}""";

        server.createContext("/status", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("status", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("rest") && output.contains("8080"));
        assertTrue(output.contains("ns1") && output.contains("3000"));
        assertTrue(output.contains("ns2") && output.contains("4000"));
    }

    @Test
    void formatDurationShouldHandleDaysInIso8601() {
        // ISO 8601 can include days
        String formatted = StatusCommand.formatDuration("P1DT2H3M4S");
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
    }
}
