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
package io.naftiko.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import io.naftiko.Cli;

public class ScriptingCommandTest {

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
    void scriptingShouldDisplayConfigAndStats() {
        String json = """
                {"enabled":true,
                 "defaultLocation":"file:///app/scripts",
                 "defaultLanguage":"javascript",
                 "timeout":3000,
                 "statementLimit":50000,
                 "allowedLanguages":["javascript","python"],
                 "stats":{
                   "totalExecutions":142,
                   "totalErrors":3,
                   "averageDurationMs":12.5,
                   "lastExecutionAt":"2026-04-21T14:30:00Z"
                 }}""";

        server.createContext("/scripting", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Scripting: ENABLED"));
        assertTrue(output.contains("file:///app/scripts"));
        assertTrue(output.contains("javascript"));
        assertTrue(output.contains("3000 ms"));
        assertTrue(output.contains("50000"));
        assertTrue(output.contains("142"));
        assertTrue(output.contains("3"));
        assertTrue(output.contains("12.50"));
    }

    @Test
    void scriptingShouldShowDisabledState() {
        String json = """
                {"enabled":false,
                 "timeout":5000,
                 "statementLimit":100000,
                 "stats":{
                   "totalExecutions":0,
                   "totalErrors":0,
                   "averageDurationMs":0.0
                 }}""";

        server.createContext("/scripting", exchange -> {
            byte[] body = json.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port));

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Scripting: DISABLED"));
    }

    @Test
    void scriptingShouldReturnOneWhenNotConfigured() {
        server.createContext("/scripting", exchange -> {
            byte[] body = "{\"error\":\"Scripting is not configured\"}".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port));

        assertEquals(1, exitCode);
        String errOutput = errCapture.toString();
        assertTrue(errOutput.contains("Scripting is not configured"));
    }

    @Test
    void scriptingShouldReturnOneWhenUnreachable() {
        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", "1");

        assertEquals(1, exitCode);
        String errOutput = errCapture.toString();
        assertTrue(errOutput.contains("Cannot connect to control port"));
    }

    @Test
    void scriptingSetShouldUpdateConfig() {
        String updatedJson = """
                {"enabled":false,
                 "defaultLocation":"file:///app/scripts",
                 "defaultLanguage":"javascript",
                 "timeout":3000,
                 "statementLimit":50000,
                 "stats":{
                   "totalExecutions":0,
                   "totalErrors":0,
                   "averageDurationMs":0.0
                 }}""";

        server.createContext("/scripting", exchange -> {
            byte[] body = updatedJson.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port),
                "--set", "enabled=false");

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Scripting configuration updated."));
        assertTrue(output.contains("Scripting: DISABLED"));
    }

    @Test
    void scriptingSetShouldRejectUnknownField() {
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port),
                "--set", "unknown=value");

        assertEquals(1, exitCode);
        String errOutput = errCapture.toString();
        assertTrue(errOutput.contains("Unknown scripting field: unknown"));
    }

    @Test
    void scriptingSetShouldUpdateMultipleFields() {
        String updatedJson = """
                {"enabled":true,
                 "timeout":10000,
                 "statementLimit":200000,
                 "stats":{
                   "totalExecutions":0,
                   "totalErrors":0,
                   "averageDurationMs":0.0
                 }}""";

        server.createContext("/scripting", exchange -> {
            byte[] body = updatedJson.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port),
                "--set", "timeout=10000", "--set", "statementLimit=200000");

        assertEquals(0, exitCode);
        String output = outCapture.toString();
        assertTrue(output.contains("Scripting configuration updated."));
        assertTrue(output.contains("10000 ms"));
        assertTrue(output.contains("200000"));
    }

    @Test
    void scriptingSetShouldUpdateAllowedLanguages() {
        String updatedJson = """
                {"enabled":true,
                 "timeout":5000,
                 "statementLimit":100000,
                 "allowedLanguages":["javascript","python"],
                 "stats":{
                   "totalExecutions":0,
                   "totalErrors":0,
                   "averageDurationMs":0.0
                 }}""";

        final String[] receivedBody = {null};
        server.createContext("/scripting", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                receivedBody[0] = new String(exchange.getRequestBody().readAllBytes());
            }
            byte[] body = updatedJson.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        CommandLine cmd = new CommandLine(new Cli());
        int exitCode = cmd.execute("scripting", "--port", String.valueOf(port),
                "--set", "allowedLanguages=javascript,python");

        assertEquals(0, exitCode);
        assertNotNull(receivedBody[0]);
        assertTrue(receivedBody[0].contains("\"allowedLanguages\""));
        assertTrue(receivedBody[0].contains("\"javascript\""));
        assertTrue(receivedBody[0].contains("\"python\""));
    }
}
