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
package io.naftiko.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Unit tests for {@link TracesResource} logic via {@link TraceRingBuffer}.
 *
 * <p>Since {@code TracesResource} is a {@link org.restlet.resource.ServerResource} instantiated
 * per-request by Restlet's router, direct unit tests target the ring buffer and trace model.
 * Full endpoint tests are covered by {@link ControlIntegrationTest}.</p>
 */
public class TracesResourceTest {

    @Test
    public void ringBufferShouldReturnTracesFilteredByStatus() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);
        buffer.add(createTrace("t1", "op-1", "OK"));
        buffer.add(createTrace("t2", "op-2", "ERROR"));
        buffer.add(createTrace("t3", "op-3", "OK"));

        List<TraceSummary> all = buffer.getAll();

        long okCount = all.stream().filter(t -> "OK".equals(t.status())).count();
        long errorCount = all.stream().filter(t -> "ERROR".equals(t.status())).count();

        assertEquals(2, okCount);
        assertEquals(1, errorCount);
    }

    @Test
    public void ringBufferShouldReturnTraceWithSpanDetails() {
        TraceSummary.SpanSummary span = new TraceSummary.SpanSummary(
                "span-1", null, "root-op", "SERVER", 42L, "OK");
        TraceSummary trace = new TraceSummary(
                "t1", "root-op", 42L, "OK", Instant.now(), 1, List.of(span));

        TraceRingBuffer buffer = new TraceRingBuffer(10);
        buffer.add(trace);

        TraceSummary found = buffer.findByTraceId("t1");
        assertNotNull(found);
        assertEquals(1, found.spans().size());
        assertEquals("span-1", found.spans().get(0).spanId());
        assertNull(found.spans().get(0).parentSpanId());
        assertEquals("SERVER", found.spans().get(0).kind());
    }

    @Test
    public void ringBufferShouldReturnNullForUnknownTraceId() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);
        buffer.add(createTrace("t1", "op-1", "OK"));

        assertNull(buffer.findByTraceId("nonexistent"));
    }

    private static TraceSummary createTrace(String traceId, String rootSpanName, String status) {
        return new TraceSummary(
                traceId, rootSpanName, 42L, status, Instant.now(), 1, List.of(
                        new TraceSummary.SpanSummary(
                                "span-1", null, rootSpanName, "SERVER", 42L, status)));
    }
}
