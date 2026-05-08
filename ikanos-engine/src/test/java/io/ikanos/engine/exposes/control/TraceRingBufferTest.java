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

import java.time.Instant;
import java.util.List;

/**
 * Unit tests for {@link TraceRingBuffer}.
 */
public class TraceRingBufferTest {

    @Test
    public void addShouldStoreTrace() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);

        buffer.add(createTrace("trace-1", "op-1"));

        assertEquals(1, buffer.getSize());
        assertEquals(10, buffer.getCapacity());
    }

    @Test
    public void getAllShouldReturnMostRecentFirst() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);

        buffer.add(createTrace("trace-1", "op-1"));
        buffer.add(createTrace("trace-2", "op-2"));
        buffer.add(createTrace("trace-3", "op-3"));

        List<TraceSummary> all = buffer.getAll();
        assertEquals(3, all.size());
        assertEquals("trace-3", all.get(0).traceId());
        assertEquals("trace-1", all.get(2).traceId());
    }

    @Test
    public void addShouldEvictOldestWhenFull() {
        TraceRingBuffer buffer = new TraceRingBuffer(2);

        buffer.add(createTrace("trace-1", "op-1"));
        buffer.add(createTrace("trace-2", "op-2"));
        buffer.add(createTrace("trace-3", "op-3"));

        assertEquals(2, buffer.getSize());
        List<TraceSummary> all = buffer.getAll();
        assertEquals("trace-3", all.get(0).traceId());
        assertEquals("trace-2", all.get(1).traceId());
    }

    @Test
    public void findByTraceIdShouldReturnMatchingTrace() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);

        buffer.add(createTrace("trace-1", "op-1"));
        buffer.add(createTrace("trace-2", "op-2"));

        TraceSummary found = buffer.findByTraceId("trace-1");
        assertNotNull(found);
        assertEquals("op-1", found.rootSpanName());
    }

    @Test
    public void findByTraceIdShouldReturnNullWhenNotFound() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);

        buffer.add(createTrace("trace-1", "op-1"));

        assertNull(buffer.findByTraceId("nonexistent"));
    }

    @Test
    public void getAllShouldReturnEmptyListForEmptyBuffer() {
        TraceRingBuffer buffer = new TraceRingBuffer(10);

        List<TraceSummary> all = buffer.getAll();
        assertTrue(all.isEmpty());
        assertEquals(0, buffer.getSize());
    }

    @Test
    public void constructorShouldRejectZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TraceRingBuffer(0));
    }

    @Test
    public void constructorShouldRejectNegativeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TraceRingBuffer(-5));
    }

    private static TraceSummary createTrace(String traceId, String rootSpanName) {
        return new TraceSummary(
                traceId, rootSpanName, 100L, "OK", Instant.now(), 1, List.of());
    }
}
