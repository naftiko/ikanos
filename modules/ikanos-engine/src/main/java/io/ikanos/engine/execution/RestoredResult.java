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
package io.ikanos.engine.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The result of a call step restored from the journal instead of run again: the upstream response
 * as the live call received it, with its status, media type and JSON body.
 *
 * <p>A call step's journaled outcome keeps two things apart (see {@link #journal}): the step's
 * output, shaped by the consumed operation's output parameters, which later steps and lookups
 * read; and the raw response, which a flow without output mappings returns from its last call
 * and an adapter's mappings read. Restoring both keeps a flow resumed after a restart answering as
 * one that ran without interruption.</p>
 */
final class RestoredResult implements ConsumedResult {

    /** Fields of a call step's journaled outcome. */
    static final String OUTPUT = "output";
    static final String STATUS = "status";
    static final String MEDIA_TYPE = "mediaType";
    static final String BODY = "body";

    private final int status;
    private final String mediaType;
    private final JsonNode body;

    /** A {@code 200} JSON response with {@code body}, or with none for a {@code null} body. */
    RestoredResult(JsonNode body) {
        this(200, null, body);
    }

    RestoredResult(int status, String mediaType, JsonNode body) {
        this.status = status;
        this.body = body == null || body.isNull() || body.isMissingNode() ? null : body;
        this.mediaType = this.body == null ? null
                : mediaType != null ? mediaType : "application/json";
    }

    /**
     * The outcome to journal for a call step: its {@code output}, and the response it read. The
     * body is left out when it equals the output, the common case of an operation without output
     * parameters, so the response is journaled once.
     *
     * @param output the step's output, or {@code null}
     * @param response the call's live result, or {@code null}
     * @throws IllegalStateException when the response has a body that is not JSON
     */
    static JsonNode journal(JsonNode output, ConsumedResult response) {
        ObjectNode journaled = IkanosMappers.json().createObjectNode();
        journaled.set(OUTPUT, output);
        if (response == null) {
            return journaled;
        }
        journaled.put(STATUS, response.status());
        if (response.hasBody()) {
            JsonNode body = bodyOf(response);
            journaled.put(MEDIA_TYPE, response.mediaType());
            if (!body.equals(output)) {
                journaled.set(BODY, body);
            }
        }
        return journaled;
    }

    /** The step output in a call step's journaled outcome. */
    static JsonNode outputOf(JsonNode journaled) {
        return journaled == null ? null : journaled.get(OUTPUT);
    }

    /** The response in a call step's journaled outcome. */
    static RestoredResult responseOf(JsonNode journaled) {
        JsonNode output = outputOf(journaled);
        if (journaled == null || !journaled.has(STATUS)) {
            // No live result was recorded: the output stands in for it.
            return new RestoredResult(output);
        }
        JsonNode body = journaled.has(MEDIA_TYPE)
                ? journaled.has(BODY) ? journaled.get(BODY) : output
                : null;
        return new RestoredResult(journaled.get(STATUS).asInt(),
                journaled.hasNonNull(MEDIA_TYPE) ? journaled.get(MEDIA_TYPE).asString() : null,
                body);
    }

    private static JsonNode bodyOf(ConsumedResult response) {
        try {
            JsonNode document = response.document();
            return document != null ? document : IkanosMappers.json().readTree(response.text());
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("The response body could not be read as JSON", e);
        }
    }

    @Override
    public Outcome outcome() {
        return Outcome.SUCCESS;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String reason() {
        return null;
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public String mediaType() {
        return mediaType;
    }

    @Override
    public String text() {
        return body != null ? body.toString() : null;
    }

    @Override
    public JsonNode document() {
        return body;
    }

    @Override
    public byte[] bytes(long maxBytes) throws IOException {
        if (body == null) {
            return null;
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new OperationStepExecutor.BinarySizeExceededException(bytes.length, maxBytes);
        }
        return bytes;
    }

    @Override
    public ConsumedOperationView operation() {
        return ConsumedOperationView.NONE;
    }
}
