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
package io.ikanos.engine.exposes.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.restlet.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP stdio transport handler.
 * 
 * Reads newline-delimited JSON-RPC messages from stdin and writes responses
 * to stdout. Designed for local IDE integration where the capability process
 * is launched as a subprocess.
 * 
 * <p>All diagnostic logging goes to stderr since stdout is reserved for
 * the JSON-RPC protocol.</p>
 */
public class StdioJsonRpcHandler implements Runnable {

    private final ProtocolDispatcher dispatcher;
    private final InputStream input;
    private final PrintStream output;
    private volatile boolean running;

    /**
     * Create a stdio handler using System.in / System.out.
     */
    public StdioJsonRpcHandler(ProtocolDispatcher dispatcher) {
        this(dispatcher, System.in, System.out);
    }

    /**
     * Create a stdio handler with explicit streams (for testing).
     */
    public StdioJsonRpcHandler(ProtocolDispatcher dispatcher,
                               InputStream input, OutputStream output) {
        this.dispatcher = dispatcher;
        this.input = input;
        this.output = (output instanceof PrintStream)
                ? (PrintStream) output
                : new PrintStream(output, true, StandardCharsets.UTF_8);
        this.running = true;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    JsonNode request = dispatcher.getMapper().readTree(line);
                    ObjectNode response = dispatcher.dispatch(request);

                    // Notifications return null — no response to write
                    if (response != null) {
                        String json = dispatcher.getMapper().writeValueAsString(response);
                        output.println(json);
                        output.flush();
                    }
                } catch (JsonProcessingException e) {
                    Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);


                    // Malformed JSON — write parse error response
                    ObjectNode errorResponse = dispatcher.buildJsonRpcError(
                            null, -32700, "Parse error: " + e.getMessage());
                    try {
                        output.println(dispatcher.getMapper().writeValueAsString(errorResponse));
                        output.flush();
                    } catch (JsonProcessingException ignored) {
                        Context.getCurrentLogger().log(Level.SEVERE, "Error serializing error response", ignored);
                        // Cannot serialize the error response — nothing more we can do
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("MCP stdio error: " + e.getMessage());
                Context.getCurrentLogger().log(Level.SEVERE, "MCP stdio error", e);
            }
        }
    }

    /**
     * Signal the handler to stop reading.
     */
    public void shutdown() {
        this.running = false;
        try {
            input.close();
        } catch (IOException ignored) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error closing input stream", ignored);
            // Best-effort close to interrupt the read loop
        }
    }

}
