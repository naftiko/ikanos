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
package io.naftiko.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI command that inspects recent traces from the engine's ring buffer via the control port.
 * Supports list mode ({@code naftiko traces}) and detail mode ({@code naftiko traces <traceId>}).
 */
@Command(
    name = "traces",
    mixinStandardHelpOptions = true,
    description = "Inspect recent traces from the engine's ring buffer."
)
public class TracesCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(TracesCommand.class);

    @Mixin
    ControlPortMixin controlPort;

    @Parameters(index = "0", description = "Trace ID to inspect (detail mode)", arity = "0..1")
    String traceId;

    @Option(names = "--operation", description = "Filter traces by operation name")
    String operation;

    @Option(names = "--status", description = "Filter traces by status (OK, ERROR)")
    String status;

    @Override
    public Integer call() {
        ControlPortClient client = new ControlPortClient(controlPort.baseUrl());

        try {
            if (traceId != null && !traceId.isBlank()) {
                return showTraceDetail(client);
            } else {
                return listTraces(client);
            }
        } catch (ControlPortClient.ControlPortUnreachableException e) {
            ControlPortMixin.printUnreachableError(controlPort.baseUrl());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.debug("Traces command failed", e);
            return 1;
        }
    }

    private int listTraces(ControlPortClient client)
            throws ControlPortClient.ControlPortUnreachableException, Exception {

        StringBuilder path = new StringBuilder("/traces");
        String sep = "?";
        if (operation != null) {
            path.append(sep).append("operation=")
                    .append(URLEncoder.encode(operation, StandardCharsets.UTF_8));
            sep = "&";
        }
        if (status != null) {
            path.append(sep).append("status=")
                    .append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        }

        ControlPortClient.ControlPortResponse response = client.get(path.toString());
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode traces = root.path("traces");

        if (!traces.isArray() || traces.isEmpty()) {
            System.out.println("No traces found.");
            return 0;
        }

        System.out.println(String.format("%-34s  %-16s  %-10s  %-6s  %-5s  %s",
                "TRACE ID", "OPERATION", "DURATION", "STATUS", "SPANS", "TIME"));

        for (JsonNode trace : traces) {
            String id = trace.path("traceId").asText();
            String op = trace.path("operation").asText();
            long durationMs = trace.path("durationMs").asLong();
            String st = trace.path("status").asText();
            int spanCount = trace.path("spanCount").asInt();
            String timestamp = trace.path("timestamp").asText();

            System.out.println(String.format("%-34s  %-16s  %-10s  %-6s  %-5d  %s",
                    id, op, formatDuration(durationMs), st, spanCount,
                    formatTimestamp(timestamp)));
        }

        return 0;
    }

    private int showTraceDetail(ControlPortClient client)
            throws ControlPortClient.ControlPortUnreachableException, Exception {

        ControlPortClient.ControlPortResponse response = client.get("/traces/" + traceId);
        if (response.statusCode() == 404) {
            System.err.println("Trace not found: " + traceId);
            return 1;
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode spans = root.path("spans");

        if (!spans.isArray() || spans.isEmpty()) {
            System.out.println("Trace " + traceId + ": no spans");
            return 0;
        }

        // Build span tree from flat list
        List<SpanInfo> spanList = new ArrayList<>();
        for (JsonNode span : spans) {
            spanList.add(new SpanInfo(
                    span.path("spanId").asText(),
                    span.path("parentSpanId").asText(null),
                    span.path("name").asText(),
                    span.path("kind").asText(),
                    span.path("durationMs").asLong(),
                    span.path("status").asText()));
        }

        // Find roots (no parent or parent not in this trace)
        Map<String, List<SpanInfo>> childrenMap = new HashMap<>();
        List<SpanInfo> roots = new ArrayList<>();
        for (SpanInfo span : spanList) {
            childrenMap.computeIfAbsent(span.spanId, k -> new ArrayList<>());
        }
        for (SpanInfo span : spanList) {
            if (span.parentSpanId == null || !childrenMap.containsKey(span.parentSpanId)) {
                roots.add(span);
            } else {
                childrenMap.computeIfAbsent(span.parentSpanId, k -> new ArrayList<>()).add(span);
            }
        }

        // Render tree
        for (int i = 0; i < roots.size(); i++) {
            renderSpan(roots.get(i), "", i == roots.size() - 1, childrenMap);
        }

        return 0;
    }

    private void renderSpan(SpanInfo span, String prefix, boolean isLast,
            Map<String, List<SpanInfo>> childrenMap) {

        String connector = prefix.isEmpty() ? "" : (isLast ? "└── " : "├── ");
        System.out.println(prefix + connector + span.name
                + " [" + span.kind + "] "
                + formatDuration(span.durationMs) + " " + span.status);

        List<SpanInfo> children = childrenMap.getOrDefault(span.spanId, List.of());
        String childPrefix = prefix.isEmpty() ? "" : prefix + (isLast ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) {
            renderSpan(children.get(i), childPrefix, i == children.size() - 1, childrenMap);
        }
    }

    static String formatDuration(long ms) {
        if (ms >= 1000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        return ms + "ms";
    }

    static String formatTimestamp(String timestamp) {
        try {
            Instant instant = Instant.parse(timestamp);
            LocalTime time = instant.atZone(ZoneId.systemDefault()).toLocalTime();
            return String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(),
                    time.getSecond());
        } catch (Exception e) {
            logger.debug("Timestamp formatting failed for '{}': {}", timestamp, e.getMessage(), e);
            return timestamp;
        }
    }

    private record SpanInfo(String spanId, String parentSpanId, String name, String kind,
            long durationMs, String status) {
    }
}
