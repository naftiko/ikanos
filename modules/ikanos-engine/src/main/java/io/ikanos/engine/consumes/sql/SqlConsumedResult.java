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
package io.ikanos.engine.consumes.sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Result of one SQL call.
 *
 * <p>On success, it holds the row document built in the invocation and returns it from
 * {@link #document()} directly. On failure, it carries an HTTP-equivalent status mapped from the
 * SQLState at the source, a generic reason and no body. The driver message reaches the log
 * only, written by the adapter: {@link #failureCause()} carries the reason, SQLState and vendor
 * code, never the driver message or its causes, since a driver message can quote SQL text, schema
 * names or values and the failure cause is recorded in telemetry.</p>
 */
final class SqlConsumedResult implements ConsumedResult {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private final ConsumedOperationView operation;
    private final JsonNode document;
    private final int status;
    private final String reason;
    private final Throwable cause;
    private String text;

    private SqlConsumedResult(ConsumedOperationView operation, JsonNode document, int status,
            String reason, Throwable cause) {
        this.operation = operation;
        this.document = document;
        this.status = status;
        this.reason = reason;
        this.cause = cause;
    }

    static SqlConsumedResult success(ConsumedOperationView operation, JsonNode document) {
        return new SqlConsumedResult(operation, document, 200, "OK", null);
    }

    /**
     * Map a failed call to a status.
     *
     * <ul>
     *   <li>SQLState class {@code 22} (data exception): 400, the caller's input caused it;</li>
     *   <li>class {@code 23} (integrity constraint): 409;</li>
     *   <li>{@link SQLTimeoutException} or SQLState {@code 57014} (query cancelled): 504;</li>
     *   <li>class {@code 08} (connection): 502, the database is unreachable;</li>
     *   <li>no free pooled connection in time, with the database reachable: 503;</li>
     *   <li>anything else (syntax, access, a wrong read-only attestation, ...): 502, a problem
     *       for the operator or the author, not the caller.</li>
     * </ul>
     */
    static SqlConsumedResult failure(ConsumedOperationView operation, SQLException e) {
        int status = status(e);
        return new SqlConsumedResult(operation, null, status, reason(status),
                sanitized(e, reason(status)));
    }

    /**
     * The failure cause handed on: the reason, SQLState and vendor code only. Callers record it in
     * telemetry with its message and causes, and a driver message can quote SQL text or bound
     * values; the original exception is logged by the adapter before this point.
     */
    static SQLException sanitized(SQLException e, String reason) {
        return new SQLException(reason, e.getSQLState(), e.getErrorCode());
    }

    /** A call that ran but returned something the adapter cannot carry: 502. */
    static SqlConsumedResult failure(ConsumedOperationView operation, SqlClientException e) {
        return new SqlConsumedResult(operation, null, 502, reason(502), e);
    }

    /**
     * A write that would change more rows than its {@code maxAffectedRows}: 409, rolled back, so
     * nothing changed.
     */
    static SqlConsumedResult tooManyRows(ConsumedOperationView operation, long changed, int max) {
        return new SqlConsumedResult(operation, null, 409,
                "The call would change more rows than allowed", new SqlClientException(
                        "would change " + changed + " rows, more than maxAffectedRows " + max));
    }

    static int status(SQLException e) {
        String state = e.getSQLState();
        if (e instanceof SQLTimeoutException || "57014".equals(state)) {
            return 504;
        }
        String sqlClass = state != null && state.length() >= 2 ? state.substring(0, 2) : "";
        return switch (sqlClass) {
            case "22" -> 400;
            case "23" -> 409;
            case "08" -> 502;
            default -> e instanceof SQLTransientConnectionException ? 503 : 502;
        };
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "The database rejected an input value";
            case 409 -> "The call conflicts with a database constraint";
            case 503 -> "No database connection available";
            case 504 -> "The database call timed out";
            default -> "The database call failed";
        };
    }

    @Override
    public Outcome outcome() {
        return Outcome.fromHttpStatus(status);
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
        return true;
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
