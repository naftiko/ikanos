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

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Custom OTel {@link SpanProcessor} that captures completed spans into a {@link TraceRingBuffer}
 * for local trace inspection via the {@code /traces} endpoint.
 *
 * <p>Spans are grouped by trace ID. When a root span (no valid remote parent) completes, all
 * accumulated spans for that trace are flushed into a {@link TraceSummary} and added to the ring
 * buffer.</p>
 */
public class TraceCapturingSpanProcessor implements SpanProcessor {

    private final TraceRingBuffer ringBuffer;
    private final Map<String, Queue<SpanData>> pendingSpans = new ConcurrentHashMap<>();

    public TraceCapturingSpanProcessor(TraceRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // No action on start
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData data = span.toSpanData();
        String traceId = data.getTraceId();

        pendingSpans.computeIfAbsent(traceId, k -> new ConcurrentLinkedQueue<>()).add(data);

        // A root span has no valid parent span ID or its parent is remote
        boolean isRoot = !data.getParentSpanContext().isValid()
                || data.getParentSpanContext().isRemote();

        if (isRoot) {
            Queue<SpanData> spans = pendingSpans.remove(traceId);
            if (spans != null) {
                TraceSummary summary = buildSummary(traceId, data, List.copyOf(spans));
                ringBuffer.add(summary);
            }
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        pendingSpans.clear();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    private TraceSummary buildSummary(String traceId, SpanData rootSpan, List<SpanData> allSpans) {
        long startEpochNanos = rootSpan.getStartEpochNanos();
        long endEpochNanos = rootSpan.getEndEpochNanos();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endEpochNanos - startEpochNanos);

        List<TraceSummary.SpanSummary> spanSummaries = new ArrayList<>();
        for (SpanData sd : allSpans) {
            long spanDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    sd.getEndEpochNanos() - sd.getStartEpochNanos());
            spanSummaries.add(new TraceSummary.SpanSummary(
                    sd.getSpanId(),
                    sd.getParentSpanContext().isValid()
                            ? sd.getParentSpanContext().getSpanId() : null,
                    sd.getName(),
                    toKindString(sd.getKind()),
                    spanDurationMs,
                    toStatusString(sd.getStatus().getStatusCode())));
        }

        return new TraceSummary(
                traceId,
                rootSpan.getName(),
                durationMs,
                toStatusString(rootSpan.getStatus().getStatusCode()),
                Instant.ofEpochSecond(
                        TimeUnit.NANOSECONDS.toSeconds(startEpochNanos),
                        startEpochNanos % 1_000_000_000L),
                allSpans.size(),
                spanSummaries);
    }

    static String toKindString(SpanKind kind) {
        return switch (kind) {
            case SERVER -> "SERVER";
            case CLIENT -> "CLIENT";
            case INTERNAL -> "INTERNAL";
            case PRODUCER -> "PRODUCER";
            case CONSUMER -> "CONSUMER";
        };
    }

    static String toStatusString(StatusCode code) {
        return switch (code) {
            case OK -> "OK";
            case ERROR -> "ERROR";
            case UNSET -> "OK";
        };
    }
}
