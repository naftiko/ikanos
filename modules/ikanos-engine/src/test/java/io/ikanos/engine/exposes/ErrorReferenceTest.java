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
package io.ikanos.engine.exposes;

import io.ikanos.engine.LogCapture;
import static io.ikanos.engine.observability.OtelNullSafety.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

public class ErrorReferenceTest {

    private static final String SENSITIVE_DETAIL = "jdbc:postgresql://internal-db:5432/orders";
    private static final Pattern UUID_PATTERN = Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Test
    public void correlationIdShouldReturnTraceIdWhenSpanIsValid() {
        SpanContext spanContext = SpanContext.create("0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331", nonNull(TraceFlags.getSampled()),
                nonNull(TraceState.getDefault()));

        try (Scope scope = Span.wrap(nonNull(spanContext)).makeCurrent()) {
            assertEquals("0af7651916cd43dd8448eb211c80319c", ErrorReference.correlationId());
        }
    }

    @Test
    public void correlationIdShouldReturnFreshUuidWhenNoValidSpan() {
        String first = ErrorReference.correlationId();

        assertTrue(UUID_PATTERN.matcher(first).matches(), first);
        assertNotEquals(first, ErrorReference.correlationId());
    }

    @Test
    public void correlationIdShouldReturnUuidWhenCurrentSpanHasInvalidContext() {
        try (Scope scope = Span.wrap(nonNull(SpanContext.getInvalid())).makeCurrent()) {
            String id = ErrorReference.correlationId();

            assertTrue(UUID_PATTERN.matcher(id).matches(), id);
        }
    }

    @Test
    public void withReferenceShouldAppendTheIdToTheGenericMessageOnly() {
        String message = ErrorReference.withReference("Internal error", "abc-123");

        assertEquals("Internal error (reference: abc-123)", message);
    }

    @Test
    public void recordShouldLogFullDetailAndStackTraceUnderTheReturnedId() {
        LogCapture logs = new LogCapture();

        try {
            String id = ErrorReference.record(Level.WARNING, "Something failed",
                    new IllegalStateException(SENSITIVE_DETAIL));

            assertTrue(logs.messages().stream()
                    .anyMatch(m -> m.contains(id) && m.contains(SENSITIVE_DETAIL)),
                    "expected a log entry with both the id and the detail");
            assertTrue(logs.events().stream().anyMatch(e -> e.getThrowableProxy() != null),
                    "expected the stack trace to be logged");
        } finally {
            logs.close();
        }
    }

    @Test
    public void withReferenceShouldNotContainExceptionDetail() {
        String id = ErrorReference.record(Level.WARNING, "Something failed",
                new IllegalStateException(SENSITIVE_DETAIL));

        String message = ErrorReference.withReference("Internal error", id);

        assertFalse(message.contains(SENSITIVE_DETAIL), message);
        assertFalse(message.contains("IllegalStateException"), message);
    }
}
