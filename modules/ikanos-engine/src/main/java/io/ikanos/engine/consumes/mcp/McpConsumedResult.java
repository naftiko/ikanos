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
package io.ikanos.engine.consumes.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;

/**
 * {@link ConsumedResult} for one successful MCP {@code tools/call}.
 *
 * <p>The response document is selected once, at construction, by
 * {@link McpClientAdapter#selectBody}: {@code structuredContent} when present, otherwise the single
 * text content (parsed as JSON when possible, raw string otherwise). Mappings then run on
 * {@link #document()} exactly as they do on an HTTP JSON body.</p>
 */
final class McpConsumedResult implements ConsumedResult {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConsumedOperationView operation;
    private final JsonNode document;
    private final String text;

    McpConsumedResult(ConsumedOperationView operation, JsonNode document, String text) {
        this.operation = operation;
        this.document = document;
        this.text = text;
    }

    @Override
    public Outcome outcome() {
        return Outcome.SUCCESS;
    }

    @Override
    public int status() {
        return 200;
    }

    @Override
    public String reason() {
        return "OK";
    }

    @Override
    public boolean hasBody() {
        return document != null || (text != null && !text.isEmpty());
    }

    @Override
    public String mediaType() {
        return document != null && !document.isTextual() ? "application/json" : "text/plain";
    }

    @Override
    public String text() throws IOException {
        if (text != null) {
            return text;
        }
        return document != null ? JSON.writeValueAsString(document) : null;
    }

    @Override
    public JsonNode document() {
        return document;
    }

    @Override
    public byte[] bytes(long maxBytes) throws IOException {
        String body = text();
        if (body == null) {
            return null;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new BinarySizeExceededException(bytes.length, maxBytes);
        }
        return bytes;
    }

    @Override
    public ConsumedOperationView operation() {
        return operation;
    }
}
