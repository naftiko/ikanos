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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import io.ikanos.engine.exposes.mcp.model.DispatchResult;
import org.restlet.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.PARSE_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

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

    /** Method of the notification a client cancels a request with. */
    static final String CANCELLED = "notifications/cancelled";

    /** The running subscriptions, by the JSON text of their request ID. */
    private final Map<String, McpSubscription> subscriptions = new ConcurrentHashMap<>();

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
        // Always encode as UTF-8, even when given a PrintStream such as System.out, whose
        // charset follows the platform (stdout.encoding): MCP messages are UTF-8 (issue #1034).
        // PrintStream.write(byte[]) passes the encoded bytes through unchanged.
        this.output = new PrintStream(output, true, StandardCharsets.UTF_8);
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
                    cancelSubscriptionIfAsked(request);
                    // Extract W3C trace context from params._meta (stdio has no HTTP headers,
                    // so _meta is the only carrier — see McpMetaTraceContextBridge), create the
                    // SERVER span, and populate MDC identically to the Streamable HTTP transport.
                    DispatchResult dispatchResult = dispatcher.dispatchWithTracing(request, null);
                    if (dispatchResult.subscription() != null) {
                        listen(dispatchResult.subscription());
                        continue;
                    }
                    ObjectNode response = dispatchResult.responseBody();

                    // Notifications return null — no response to write
                    if (response != null) {
                        writeLine(dispatcher.getMapper().writeValueAsString(response));
                    }
                } catch (JacksonException e) {
                    Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);


                    // Malformed JSON — write parse error response
                    ObjectNode errorResponse = buildJsonRpcError(
                            null, PARSE_ERROR.getCode(), "Parse error: " + e.getMessage());
                    try {
                        writeLine(dispatcher.getMapper().writeValueAsString(errorResponse));
                    } catch (JacksonException ex) {
                        Context.getCurrentLogger().log(Level.SEVERE, "Error serializing error response", ex);
                        // Cannot serialize the error response — nothing more we can do
                    }
                }
            }
        } catch (JacksonException | IOException e) {
            if (running) {
                System.err.println("MCP stdio error: " + e.getMessage());
                Context.getCurrentLogger().log(Level.SEVERE, "MCP stdio error", e);
            }
        } finally {
            // The client is gone: its subscriptions end with it, and nothing is left to answer.
            subscriptions.values().forEach(McpSubscription::cancel);
        }
    }

    /**
     * Writes one message, as one line. Subscriptions write from their own threads, so lines are
     * written one at a time and never interleave.
     */
    private void writeLine(String json) {
        synchronized (output) {
            output.println(json);
            output.flush();
        }
    }

    /**
     * Runs a subscription on its own virtual thread: every subscription shares stdout with the
     * responses, its messages told apart by their subscription ID.
     */
    private void listen(McpSubscription subscription) {
        String key = subscription.id().toString();
        McpSubscription previous = subscriptions.put(key, subscription);
        if (previous != null) {
            // A client reusing an ID it still listens on: the newer request replaces the older.
            previous.cancel();
        }
        Thread.ofVirtual().name("mcp-stdio-subscription").start(() -> {
            try {
                subscription.run(message -> writeLine(
                        dispatcher.getMapper().writeValueAsString(message)));
            } catch (IOException | RuntimeException e) {
                Context.getCurrentLogger().log(Level.WARNING, "MCP stdio subscription ended", e);
            } finally {
                subscriptions.remove(key, subscription);
            }
        });
    }

    /**
     * Cancels the subscription a client's {@code notifications/cancelled} names by its
     * {@code requestId}: on stdio, that is how a client ends a {@code subscriptions/listen}.
     */
    private void cancelSubscriptionIfAsked(JsonNode message) {
        if (message.isObject() && !message.has("id")
                && CANCELLED.equals(message.path("method").asString(""))) {
            McpSubscription subscription = subscriptions
                    .get(message.path("params").path("requestId").toString());
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    /** The number of running subscriptions, for the tests. */
    int subscriptionCount() {
        return subscriptions.size();
    }

    /**
     * Signal the handler to stop reading.
     */
    public void shutdown() {
        this.running = false;
        try {
            input.close();
        } catch (IOException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error closing input stream", e);
            // Best-effort close to interrupt the read loop
        }
    }

}
