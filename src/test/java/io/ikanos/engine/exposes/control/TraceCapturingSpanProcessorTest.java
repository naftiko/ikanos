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
package io.ikanos.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Unit tests for {@link TraceCapturingSpanProcessor} helper methods.
 */
public class TraceCapturingSpanProcessorTest {

    @Test
    public void toKindStringShouldMapAllSpanKinds() {
        assertEquals("SERVER", TraceCapturingSpanProcessor.toKindString(SpanKind.SERVER));
        assertEquals("CLIENT", TraceCapturingSpanProcessor.toKindString(SpanKind.CLIENT));
        assertEquals("INTERNAL", TraceCapturingSpanProcessor.toKindString(SpanKind.INTERNAL));
        assertEquals("PRODUCER", TraceCapturingSpanProcessor.toKindString(SpanKind.PRODUCER));
        assertEquals("CONSUMER", TraceCapturingSpanProcessor.toKindString(SpanKind.CONSUMER));
    }

    @Test
    public void toStatusStringShouldMapOkToOk() {
        assertEquals("OK", TraceCapturingSpanProcessor.toStatusString(StatusCode.OK));
    }

    @Test
    public void toStatusStringShouldMapErrorToError() {
        assertEquals("ERROR", TraceCapturingSpanProcessor.toStatusString(StatusCode.ERROR));
    }

    @Test
    public void toStatusStringShouldMapUnsetToOk() {
        assertEquals("OK", TraceCapturingSpanProcessor.toStatusString(StatusCode.UNSET));
    }
}
