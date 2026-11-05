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
import tools.jackson.databind.JsonNode;

/**
 * The result of a call step restored from the journal instead of run again: a successful JSON
 * response whose body is the step's journaled output.
 *
 * <p>It stands in for the live response a flow without output mappings returns from its last
 * call. The journaled output is already shaped by the consumed operation's output parameters, so
 * {@link #operation()} declares none, and reading the body again yields the same object.</p>
 */
final class RestoredResult implements ConsumedResult {

    private final JsonNode body;

    /** @param body the journaled output, or {@code null} for a step that produced none */
    RestoredResult(JsonNode body) {
        this.body = body == null || body.isNull() || body.isMissingNode() ? null : body;
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
        return body != null;
    }

    @Override
    public String mediaType() {
        return body != null ? "application/json" : null;
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
