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

import java.time.Instant;
import java.util.List;

/**
 * Immutable summary of a completed trace.
 */
public record TraceSummary(
        String traceId,
        String rootSpanName,
        long durationMs,
        String status,
        Instant timestamp,
        int spanCount,
        List<SpanSummary> spans) {

    /**
     * Summary of a single span within a trace.
     */
    public record SpanSummary(
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            long durationMs,
            String status) {
    }
}
