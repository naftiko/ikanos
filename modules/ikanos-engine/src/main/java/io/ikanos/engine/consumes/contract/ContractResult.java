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
package io.ikanos.engine.consumes.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The result of a contract-described consumed call: a JSON result document on success, or an
 * HTTP-equivalent status with a reason and a cause on failure. The protocol's own code (a gRPC
 * status, GraphQL errors, a Smithy error shape) is mapped to the status by the adapter.
 */
public final class ContractResult implements ConsumedResult {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private final ConsumedOperationView operation;
    private final JsonNode document;
    private final boolean nativeDocument;
    private final int status;
    private final String reason;
    private final Throwable cause;
    private final String nativeCode;
    private volatile String text;

    private ContractResult(ConsumedOperationView operation, JsonNode document,
            boolean nativeDocument, int status, String reason, Throwable cause, String nativeCode) {
        this.operation = operation;
        this.document = document;
        this.nativeDocument = nativeDocument;
        this.status = status;
        this.reason = reason;
        this.cause = cause;
        this.nativeCode = nativeCode;
    }

    /**
     * @param operation the operation's output declarations
     * @param document the result document
     * @param nativeDocument whether the document keeps values JSON text would round
     * @return a 200 result
     */
    public static ContractResult success(ConsumedOperationView operation, JsonNode document,
            boolean nativeDocument) {
        return new ContractResult(operation, document, nativeDocument, 200, "OK", null, null);
    }

    /**
     * @param operation the operation's output declarations
     * @param status the HTTP-equivalent status; 0 when no answer was received
     * @param reason a bounded, human-readable reason
     * @param cause the failure, kept for the log, or {@code null}
     * @param nativeCode the protocol's own code, for the client span, or {@code null}
     * @return a failed result
     */
    public static ContractResult failure(ConsumedOperationView operation, int status,
            String reason, Throwable cause, String nativeCode) {
        return new ContractResult(operation, null, false, status, reason, cause, nativeCode);
    }

    /** @return the protocol's own code, or {@code null} */
    public String nativeCode() {
        return nativeCode;
    }

    @Override
    public Outcome outcome() {
        return status <= 0 ? Outcome.UPSTREAM_ERROR : Outcome.fromHttpStatus(status);
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public Throwable failureCause() {
        return cause;
    }

    @Override
    public boolean hasBody() {
        return document != null;
    }

    @Override
    public String mediaType() {
        return document != null ? "application/json" : null;
    }

    @Override
    public String text() throws IOException {
        if (text == null && document != null) {
            text = JSON.writeValueAsString(document);
        }
        return text;
    }

    @Override
    public JsonNode document() {
        return document;
    }

    @Override
    public boolean nativeDocument() {
        return nativeDocument;
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
