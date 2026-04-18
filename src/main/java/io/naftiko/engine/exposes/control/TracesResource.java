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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import java.util.List;

/**
 * Trace inspection endpoint. Returns recent trace summaries from the ring buffer
 * ({@code /traces}) or the full span tree for a specific trace ({@code /traces/{traceId}}).
 */
public class TracesResource extends ServerResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Get("json")
    public Representation getTraces() {
        TraceRingBuffer ringBuffer =
                (TraceRingBuffer) getContext().getAttributes().get("traceRingBuffer");

        String traceId = getAttribute("traceId");

        if (traceId != null && !traceId.isEmpty()) {
            return handleSingleTrace(traceId, ringBuffer);
        } else {
            return handleTraceList(ringBuffer);
        }
    }

    private Representation handleTraceList(TraceRingBuffer ringBuffer) {
        List<TraceSummary> traces = ringBuffer.getAll();

        // Optional query filters
        String operationFilter = getQuery() != null
                ? getQuery().getFirstValue("operation") : null;
        String statusFilter = getQuery() != null
                ? getQuery().getFirstValue("status") : null;

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode tracesNode = MAPPER.createArrayNode();

        for (TraceSummary trace : traces) {
            if (operationFilter != null && !trace.rootSpanName().contains(operationFilter)) {
                continue;
            }
            if (statusFilter != null && !trace.status().equalsIgnoreCase(statusFilter)) {
                continue;
            }

            ObjectNode traceNode = MAPPER.createObjectNode();
            traceNode.put("traceId", trace.traceId());
            traceNode.put("operation", trace.rootSpanName());
            traceNode.put("durationMs", trace.durationMs());
            traceNode.put("status", trace.status());
            traceNode.put("spanCount", trace.spanCount());
            traceNode.put("timestamp", trace.timestamp().toString());
            tracesNode.add(traceNode);
        }

        root.set("traces", tracesNode);
        root.put("bufferSize", ringBuffer.getCapacity());
        root.put("bufferUsed", ringBuffer.getSize());

        return new StringRepresentation(root.toString(), MediaType.APPLICATION_JSON);
    }

    private Representation handleSingleTrace(String traceId, TraceRingBuffer ringBuffer) {
        TraceSummary trace = ringBuffer.findByTraceId(traceId);

        if (trace == null) {
            setStatus(Status.CLIENT_ERROR_NOT_FOUND);
            return new StringRepresentation(
                    "{\"error\":\"Trace not found: " + traceId + "\"}",
                    MediaType.APPLICATION_JSON);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("traceId", trace.traceId());
        root.put("operation", trace.rootSpanName());
        root.put("durationMs", trace.durationMs());
        root.put("status", trace.status());
        root.put("timestamp", trace.timestamp().toString());

        ArrayNode spansNode = MAPPER.createArrayNode();
        for (TraceSummary.SpanSummary span : trace.spans()) {
            ObjectNode spanNode = MAPPER.createObjectNode();
            spanNode.put("spanId", span.spanId());
            if (span.parentSpanId() != null) {
                spanNode.put("parentSpanId", span.parentSpanId());
            }
            spanNode.put("name", span.name());
            spanNode.put("kind", span.kind());
            spanNode.put("durationMs", span.durationMs());
            spanNode.put("status", span.status());
            spansNode.add(spanNode);
        }
        root.set("spans", spansNode);

        return new StringRepresentation(root.toString(), MediaType.APPLICATION_JSON);
    }
}
