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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.ikanos.Capability;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.representation.StringRepresentation;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-regression tests for issue #548: during a {@code tools/call} MCP request processed through
 * {@link McpServerResource}, the Logback MDC must contain non-empty {@code trace_id} and
 * {@code span_id} keys for log-trace correlation to work.
 *
 * <p>The underlying cause: {@code logback.xml} referenced {@code %X{trace_id}} / {@code %X{span_id}}
 * but did not include the {@code OpenTelemetryAppender} that populates those MDC keys. As a result,
 * both fields were always empty regardless of whether a span existed.</p>
 */
class ObservabilityMcpHttpLayerTest {

    private static final String SCHEMA_VERSION = VersionHelper.getSchemaVersion();

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @BeforeEach
    void setUp() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(OtelTestFixtures.tracerProvider(exporter))
                .setPropagators(OtelTestFixtures.w3cPropagators())
                .build();
        TelemetryBootstrap.init(sdk);
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
        MDC.clear();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static String weatherCapabilityYaml() {
        return """
                ikanos: "%s"
                info:
                  display: "weather-capability"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "localhost"
                      port: 0
                      namespace: "weather"
                      tools:
                        - name: "get-forecast"
                          description: "Returns the weather forecast for a location"
                          ref: "forecast.get-forecast"
                          inputParameters:
                            - name: "location"
                              type: "string"
                              description: "City name"
                              required: true
                  aggregates:
                    - namespace: "forecast"
                      flows:
                        - name: "get-forecast"
                          inputParameters:
                            - name: "location"
                              type: "string"
                          outputParameters:
                            - name: "summary"
                              type: "string"
                              value: "Sunny"
                  consumes: []
                """.formatted(SCHEMA_VERSION);
    }

    /**
     * Builds a {@link McpServerResource} wired in-process without starting a real HTTP server,
     * mirroring what {@link McpServerAdapter#initHttpTransport} does at runtime.
     */
    private McpServerResource buildResource(Capability capability) {
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(adapter);
        Map<String, Boolean> sessions = new ConcurrentHashMap<>();

        Context ctx = new Context();
        ctx.getAttributes().put("dispatcher", dispatcher);
        ctx.getAttributes().put("activeSessions", sessions);
        ctx.getAttributes().put("capabilityName", "weather-capability");

        McpServerResource resource = new McpServerResource();
        Request request = new Request(Method.POST, "http://localhost/mcp");
        Response response = new Response(request);
        resource.init(ctx, request, response);
        return resource;
    }

    private static StringRepresentation toolsCallBody(String toolName) {
        return new StringRepresentation(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",\
                "params":{"name":"%s","arguments":{"location":"Paris"}}}""".formatted(toolName),
                MediaType.APPLICATION_JSON);
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    /**
     * Regression test for issue #548.
     *
     * <p>During the execution of a {@code tools/call} request, the OTel span context must be
     * bridged into the Logback MDC so that {@code %X{trace_id}} and {@code %X{span_id}} are
     * non-empty in log lines. Without the {@code OpenTelemetryAppender} in {@code logback.xml},
     * these values are always empty.</p>
     */
    @Test
    void mcpToolsCallShouldPopulateMdcTraceIdAndSpanIdDuringExecution() throws Exception {
        Capability capability = capabilityFromYaml(weatherCapabilityYaml());
        McpServerResource resource = buildResource(capability);

        // Capture MDC state from inside the span scope by intercepting via a custom
        // ProtocolDispatcher subclass is not possible without reflection. Instead we rely
        // on the OTel → MDC bridge being active: after handlePost() completes, the span is
        // ended and the MDC is cleared. We therefore capture a snapshot mid-flight via a
        // capturing Restlet context attribute set by an instrumented dispatcher.
        //
        // Simpler approach: wrap the handlePost call inside a scope where we can observe MDC.
        // The OpenTelemetry Logback appender populates MDC synchronously within the span scope.
        // We verify by asserting that the MDC was non-empty at some point during the call.
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedSpanId = new AtomicReference<>();

        // Re-wire the dispatcher so we can observe MDC during dispatch
        McpServerAdapter adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ProtocolDispatcher capturingDispatcher = new ProtocolDispatcher(adapter) {
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode dispatch(
                    com.fasterxml.jackson.databind.JsonNode root) {
                // At this point we are inside the SERVER span scope created by McpServerResource
                capturedTraceId.set(MDC.get("trace_id"));
                capturedSpanId.set(MDC.get("span_id"));
                return super.dispatch(root);
            }
        };

        Map<String, Boolean> sessions = new ConcurrentHashMap<>();
        Context ctx = new Context();
        ctx.getAttributes().put("dispatcher", capturingDispatcher);
        ctx.getAttributes().put("activeSessions", sessions);
        ctx.getAttributes().put("capabilityName", "weather-capability");

        McpServerResource observedResource = new McpServerResource();
        Request request = new Request(Method.POST, "http://localhost/mcp");
        Response response = new Response(request);
        observedResource.init(ctx, request, response);

        observedResource.handlePost(toolsCallBody("get-forecast"));

        assertNotNull(capturedTraceId.get(),
                "MDC trace_id must be non-null inside the span scope (issue #548: "
                + "OpenTelemetryAppender missing from logback.xml)");
        assertFalse(capturedTraceId.get().isBlank(),
                "MDC trace_id must not be blank inside the span scope — was: '"
                + capturedTraceId.get() + "'");

        assertNotNull(capturedSpanId.get(),
                "MDC span_id must be non-null inside the span scope (issue #548)");
        assertFalse(capturedSpanId.get().isBlank(),
                "MDC span_id must not be blank inside the span scope — was: '"
                + capturedSpanId.get() + "'");
    }

    /**
     * The MDC must be cleared after the request completes so that subsequent unrelated log lines
     * from the same thread do not carry a stale traceId.
     */
    @Test
    void mcpToolsCallShouldClearMdcAfterRequestCompletes() throws Exception {
        Capability capability = capabilityFromYaml(weatherCapabilityYaml());
        McpServerResource resource = buildResource(capability);

        resource.handlePost(toolsCallBody("get-forecast"));

        // After the span scope exits, the OTel Logback bridge must have cleared the MDC keys
        String traceIdAfter = MDC.get("trace_id");
        String spanIdAfter = MDC.get("span_id");

        assertTrue(traceIdAfter == null || traceIdAfter.isBlank(),
                "MDC trace_id must be blank after span scope ends — was: '" + traceIdAfter + "'");
        assertTrue(spanIdAfter == null || spanIdAfter.isBlank(),
                "MDC span_id must be blank after span scope ends — was: '" + spanIdAfter + "'");
    }
}
