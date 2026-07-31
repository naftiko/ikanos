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

import com.fasterxml.jackson.databind.JsonNode;
import io.ikanos.engine.exposes.mcp.handler.PromptsGetHandler;
import io.ikanos.engine.exposes.mcp.handler.ResourcesReadHandler;
import io.ikanos.engine.exposes.mcp.handler.ToolsCallHandler;
import org.restlet.data.Header;
import org.restlet.util.Series;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An enumeration of MCP headers.
 */
public enum McpHeader {

    MCP_PROTOCOL_VERSION("MCP-Protocol-Version", List.of("params", "_meta", "io.modelcontextprotocol/protocolVersion"), false),
    MCP_METHOD("Mcp-Method", List.of("method"), false),
    MCP_NAME("Mcp-Name", Collections.emptyList(), true) {
        static final Map<String, List<String>> MCP_NAME_BY_METHOD_NAME = Map.of(
                ToolsCallHandler.METHOD_NAME, List.of("params", "name"),
                ResourcesReadHandler.METHOD_NAME, List.of("params", "uri"),
                PromptsGetHandler.METHOD_NAME, List.of("params", "name")
        );

        /**
         * The Mcp-Name header has a specificity when it comes to the path.
         * Depending on the method, it could either be: params->name or params->uri.
         * @param methodName the MCP method's name.
         * @return the path to use.
         */
        @Override
        protected List<String> getPath(String methodName) {
            return MCP_NAME_BY_METHOD_NAME.getOrDefault(methodName, Collections.emptyList());
        }
    };

    /**
     * Marker prefix/suffix wrapping a base64-encoded value inside an MCP header, e.g.
     * {@code X-Custom-Header: =?base64?SGVsbG8gV29ybGQ=?=}. Mirrors the MIME
     * "encoded-word" convention ({@code =?charset?encoding?encoded-text?=}) so
     * non-ASCII or binary header values can travel safely over transports that only
     * support ASCII headers. A header value is treated as base64-encoded only when it
     * starts with {@code ENCODING_PREFIX} and ends with {@code ENCODING_SUFFIX}; the
     * substring between the two markers is decoded, otherwise the raw value is used
     * as-is.
     */
    private static final String ENCODING_PREFIX = "=?base64?";
    private static final String ENCODING_SUFFIX = "?=";
    private final String headerName;
    private final List<String> path;
    private final boolean canBeB64Encoded;

    McpHeader(String headerName, List<String> path, boolean canBeB64Encoded) {
        this.headerName = headerName;
        this.path = path;
        this.canBeB64Encoded = canBeB64Encoded;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String extractValueFromRequestBody(String methodName, JsonNode jsonrpcRequestBody) {
        JsonNode node = jsonrpcRequestBody;
        for (String pathPart : getPath(methodName)) {
            node = node.path(pathPart);
        }
        return node.asText();
    }

    protected List<String> getPath(String methodName) {
        return path;
    }

    /**
     * Get the value of the header from a set of headers.
     * Some headers might have their value encrypted in Base64,
     * the pattern is: =?base64?{BASE64_ENCODED_VALUE}?=.
     * @param headers the headers set.
     * @return the value of the header if found, null otherwise.
     * @throws IllegalArgumentException if an error occurs while decoding an encoded value.
     */
    public String getHeaderValue(Series<Header> headers) {
        for (Header header : headers) {
            // The spec states that the header names' comparisons must be case-insensitive
            // See: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#server-validation
            if (headerName.equalsIgnoreCase(header.getName())) {
                if (canBeB64Encoded && header.getValue().startsWith(ENCODING_PREFIX) && header.getValue().endsWith(ENCODING_SUFFIX)) {
                    String encodedValue = header.getValue().substring(ENCODING_PREFIX.length(), header.getValue().length() - ENCODING_SUFFIX.length());
                    byte[] decoded = Base64.getDecoder().decode(encodedValue);
                    return new String(decoded, StandardCharsets.UTF_8); // The spec states that the UTF-8 representation is used for encoding
                } else {
                    return header.getValue();
                }
            }
        }

        return null;
    }
}
