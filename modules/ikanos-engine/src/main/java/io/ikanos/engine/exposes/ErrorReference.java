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

import java.util.UUID;
import java.util.logging.Level;
import org.restlet.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Keeps internal exception detail out of responses sent to callers.
 *
 * <p>An exception's text can carry the exception class, package paths, upstream URLs, driver-level
 * detail or parameter values. Adapters therefore answer an unexpected failure with a generic
 * message plus a correlation identifier, and the full detail is logged server-side under that same
 * identifier so an operator can find it from a caller's report.</p>
 *
 * <p>The identifier is the current OpenTelemetry trace id, which is also what the log pattern
 * prints and what the control port's {@code /traces/{traceId}} looks up. When telemetry is
 * disabled there is no valid span, so a random UUID is generated instead.</p>
 */
public final class ErrorReference {

    private ErrorReference() {}

    /**
     * Logs the full exception under a correlation identifier and returns that identifier.
     *
     * @param level log level for the entry
     * @param context short description of what failed, for the operator reading the log
     * @param cause the failure; logged as the entry's throwable (message and stack trace), never
     *        returned to the caller
     * @return the correlation identifier to return to the caller
     */
    public static String record(Level level, String context, Throwable cause) {
        String correlationId = correlationId();
        Context.getCurrentLogger().log(level, context + " [ref=" + correlationId + "]", cause);
        return correlationId;
    }

    /** Appends the correlation identifier to a generic, caller-safe message. */
    public static String withReference(String publicMessage, String correlationId) {
        return publicMessage + " (reference: " + correlationId + ")";
    }

    /**
     * The current OpenTelemetry trace id, or a random UUID when there is no valid span.
     * Package-private so it can be unit-tested directly; callers outside this class use
     * {@link #record}, which also logs.
     */
    static String correlationId() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : UUID.randomUUID().toString();
    }
}
