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

public class HealthCommandTest {

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
    void healthShouldReturnZeroWhenAllUp() {
        server.createContext("/health/live", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.createContext("/health/ready", exchange -> {
            byte[] body = ("{\"status\":\"UP\",\"adapters\":["
                    + "{\"type\":\"mcp\",\"port\":3000,\"state\":\"started\"},"
                    + "{\"type\":\"control\",\"port\":" + port + ",\"state\":\"started\"}"
                    + "]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Liveness:  UP"));
        assertTrue(output.contains("Readiness: UP"));
        assertTrue(output.contains("2/2 adapters started"));
    }

    @Test
    void healthShouldReturnOneWhenDegraded() {
        server.createContext("/health/live", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.createContext("/health/ready", exchange -> {
            byte[] body = ("{\"status\":\"DEGRADED\",\"adapters\":["
                    + "{\"type\":\"mcp\",\"port\":3000,\"state\":\"started\"},"
                    + "{\"type\":\"rest\",\"port\":8080,\"state\":\"stopped\","
                    + "\"reason\":\"Port 8080 already in use\"}"
                    + "]}").getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", String.valueOf(port));

        assertEquals(1, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("DEGRADED"));
        assertTrue(output.contains("1/2 adapters started"));
        assertTrue(output.contains("Port 8080 already in use"));
    }

    @Test
    void healthShouldReturnOneWhenUnreachable() {
        // Server not started — port will refuse connections
        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", "1");

        assertEquals(1, exitCode);
        String errOutput = errCapture.toString();
        assertTrue(errOutput.contains("Cannot connect to control port"));
    }

    @Test
    void healthShouldHandleEmptyAdaptersList() {
        server.createContext("/health/live", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.createContext("/health/ready", exchange -> {
            byte[] body = "{\"status\":\"UP\",\"adapters\":[]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("0/0 adapters"));
    }

    @Test
    void healthShouldDisplayAdapterReasonWhenPresent() {
        server.createContext("/health/live", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.createContext("/health/ready", exchange -> {
            byte[] body = ("{\"status\":\"DEGRADED\",\"adapters\":["
                    + "{\"type\":\"mcp\",\"port\":3000,\"state\":\"stopped\","
                    + "\"reason\":\"Connection timeout\"}"
                    + "]}").getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", String.valueOf(port));

        assertEquals(1, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Connection timeout"));
    }

    @Test
    void healthShouldHandleAdapterWithoutReason() {
        server.createContext("/health/live", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.createContext("/health/ready", exchange -> {
            byte[] body = ("{\"status\":\"UP\",\"adapters\":["
                    + "{\"type\":\"rest\",\"port\":8080,\"state\":\"started\"}"
                    + "]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("health", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("started"));
    }
}
