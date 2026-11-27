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
package io.ikanos.engine.exposes.mcp.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The client capabilities an MCP {@code 2026-07-28} request declares for itself, in
 * {@code params._meta["io.modelcontextprotocol/clientCapabilities"]}. Capabilities are declared
 * per request, so a decision such as returning a task handle is taken per request too.
 */
public final class RequestClientCapabilities {

    /** The {@code _meta} key that carries the capabilities. */
    public static final String META_KEY = "io.modelcontextprotocol/clientCapabilities";

    /** Identifier of the tasks extension (SEP-2663). */
    public static final String TASKS_EXTENSION = "io.modelcontextprotocol/tasks";

    /** The elicitation modes a client may declare in its {@code elicitation} capability. */
    private static final String FORM = "form";
    private static final String URL = "url";

    private static final RequestClientCapabilities NONE =
            new RequestClientCapabilities(MissingNode.getInstance());

    private final JsonNode capabilities;

    private RequestClientCapabilities(JsonNode capabilities) {
        this.capabilities = capabilities;
    }

    /** Reads the capabilities of a request from its {@code params}, which may be {@code null}. */
    public static RequestClientCapabilities from(JsonNode params) {
        if (params == null) {
            return NONE;
        }
        JsonNode node = params.path("_meta").path(META_KEY);
        return node.isObject() ? new RequestClientCapabilities(node) : NONE;
    }

    /** Whether the request declares the extension {@code id}. */
    public boolean hasExtension(String id) {
        JsonNode extensions = capabilities.path("extensions");
        return extensions.isObject() && extensions.has(id);
    }

    /** Whether the request declares the tasks extension. */
    public boolean supportsTasks() {
        return hasExtension(TASKS_EXTENSION);
    }

    /**
     * Whether the request declares that the client can answer elicitation requests, in any mode.
     */
    public boolean supportsElicitation() {
        return capabilities.path("elicitation").isObject();
    }

    /**
     * Whether the client can answer form mode elicitation requests: it declares {@code form} in
     * its {@code elicitation} capability, or declares no mode at all ({@code elicitation: {}}),
     * which means form only.
     */
    public boolean supportsFormElicitation() {
        JsonNode elicitation = capabilities.path("elicitation");
        return elicitation.isObject() && (elicitation.isEmpty() || elicitation.has(FORM));
    }

    /**
     * Whether the client can answer URL mode elicitation requests: it declares {@code url} in its
     * {@code elicitation} capability. A server must not send it a URL request otherwise.
     */
    public boolean supportsUrlElicitation() {
        JsonNode elicitation = capabilities.path("elicitation");
        return elicitation.isObject() && elicitation.has(URL);
    }

    /** Whether the client can answer elicitation requests in {@code mode}, form or url. */
    public boolean supportsElicitation(String mode) {
        return URL.equals(mode) ? supportsUrlElicitation() : supportsFormElicitation();
    }
}
