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

public class TracesCommandTest {

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
    void tracesShouldListRecentTraces() {
        String json = """
                {"traces":[
                  {"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","operation":"get-forecast",
                   "durationMs":342,"status":"OK","spanCount":4,
                   "timestamp":"2026-04-18T14:32:01Z"},
                  {"traceId":"00f067aa0ba902b7","operation":"list-pages",
                   "durationMs":1200,"status":"ERROR","spanCount":3,
                   "timestamp":"2026-04-18T14:31:58Z"}
                ],"bufferSize":100,"bufferUsed":2}""";

        server.createContext("/traces", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("traces", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("TRACE ID"));
        assertTrue(output.contains("get-forecast"));
        assertTrue(output.contains("list-pages"));
        assertTrue(output.contains("342ms"));
        assertTrue(output.contains("1.2s"));
    }

    @Test
    void tracesShouldShowEmptyMessage() {
        server.createContext("/traces", exchange -> {
            byte[] body = "{\"traces\":[],\"bufferSize\":100,\"bufferUsed\":0}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("traces", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        assertTrue(outCapture.toString().contains("No traces found."));
    }

    @Test
    void tracesShouldShowDetailForSpecificTrace() {
        String json = """
                {"traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
                 "operation":"get-forecast","durationMs":342,"status":"OK",
                 "timestamp":"2026-04-18T14:32:01Z",
                 "spans":[
                   {"spanId":"s1","name":"mcp.request","kind":"SERVER",
                    "durationMs":342,"status":"OK"},
                   {"spanId":"s2","parentSpanId":"s1","name":"step.call","kind":"INTERNAL",
                    "durationMs":320,"status":"OK"}
                 ]}""";

        server.createContext("/traces/4bf92f3577b34da6a3ce929d0e0e4736", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("traces", "--port", String.valueOf(port),
                "4bf92f3577b34da6a3ce929d0e0e4736");

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("mcp.request"));
        assertTrue(output.contains("step.call"));
        assertTrue(output.contains("SERVER"));
        assertTrue(output.contains("INTERNAL"));
    }

    @Test
    void tracesShouldReturnOneWhenTraceNotFound() {
        server.createContext("/traces/nonexistent", exchange -> {
            byte[] body = "{\"error\":\"Trace not found: nonexistent\"}".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("traces", "--port", String.valueOf(port), "nonexistent");

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Trace not found"));
    }

    @Test
    void tracesShouldReturnOneWhenUnreachable() {
        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("traces", "--port", "1");

        assertEquals(1, exitCode);
        assertTrue(errCapture.toString().contains("Cannot connect to control port"));
    }

    @Test
    void formatDurationShouldFormatMilliseconds() {
        assertEquals("342ms", TracesCommand.formatDuration(342));
    }

    @Test
    void formatDurationShouldFormatSeconds() {
        assertEquals("1.2s", TracesCommand.formatDuration(1200));
    }

    @Test
    void formatTimestampShouldFormatIso8601() {
        String formatted = TracesCommand.formatTimestamp("2026-04-18T14:32:01Z");
        // Result depends on local timezone but should not be the raw ISO string
        assertFalse(formatted.contains("T"));
        assertTrue(formatted.contains(":"));
    }
}
