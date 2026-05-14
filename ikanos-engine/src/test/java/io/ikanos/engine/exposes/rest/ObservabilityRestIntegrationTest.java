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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.*;

import io.ikanos.Capability;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;

import java.util.List;

/**
 * Integration tests verifying that REST adapter requests produce the expected OTel span hierarchy,
 * including W3C traceparent extraction.
 */
public class ObservabilityRestIntegrationTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private static String schemaVersion;

    @BeforeEach
    void setUp() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider())
                .setPropagators(contextPropagators())
                .build();
        TelemetryBootstrap.init(sdk);
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @AfterEach
    void tearDown() {
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    private Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    @Test
    void restHandleShouldProduceServerSpanWithCorrectAttributes() throws Exception {
        Capability capability = capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                              outputParameters:
                                - name: "status"
                                  type: "string"
                                  value: "ok"
                  consumes: []
                """.formatted(schemaVersion));

        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().get(0));

        Request request = new Request(Method.GET, "http://localhost/orders");
        Response response = new Response(request);
        restlet.handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Mock response should produce 1 server span (no HTTP call)");

        SpanData serverSpan = spans.get(0);
        assertEquals("rest.request", serverSpan.getName());
        assertEquals(SpanKind.SERVER, serverSpan.getKind());
        assertEquals("rest",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("GET",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_HTTP_METHOD));
        assertNotNull(stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_OPERATION_ID));
    }

    @Test
    void restHandleShouldExtractInboundTraceparent() throws Exception {
        Capability capability = capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                              outputParameters:
                                - name: "status"
                                  type: "string"
                                  value: "ok"
                  consumes: []
                """.formatted(schemaVersion));

        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().get(0));

        Request request = new Request(Method.GET, "http://localhost/orders");
        // Inject W3C traceparent header on the inbound request
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        request.getHeaders().add("traceparent", traceparent);
        Response response = new Response(request);
        restlet.handle(request, response);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData serverSpan = spans.get(0);
        // The server span should be a child of the extracted trace context
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", serverSpan.getTraceId(),
                "Server span should continue the inbound trace");
        assertEquals("00f067aa0ba902b7", serverSpan.getParentSpanId(),
                "Server span parent should be the inbound span");
    }

        @Nonnull
        private SdkTracerProvider tracerProvider() {
                return java.util.Objects.requireNonNull(SdkTracerProvider.builder()
                                .addSpanProcessor(spanProcessor())
                                .build());
        }

        @Nonnull
        private io.opentelemetry.sdk.trace.SpanProcessor spanProcessor() {
                return java.util.Objects.requireNonNull(SimpleSpanProcessor.create(spanExporter()));
        }

        @Nonnull
        private io.opentelemetry.sdk.trace.export.SpanExporter spanExporter() {
                return java.util.Objects.requireNonNull(exporter);
        }

        @Nonnull
        private ContextPropagators contextPropagators() {
                return java.util.Objects.requireNonNull(ContextPropagators.create(textMapPropagator()));
        }

        @Nonnull
        private TextMapPropagator textMapPropagator() {
                return java.util.Objects.requireNonNull(W3CTraceContextPropagator.getInstance());
        }

        private static String stringAttribute(Attributes attributes, AttributeKey<String> key) {
                return attributes.get(java.util.Objects.requireNonNull(key));
        }

    @Test
    void restHandleShouldReturnNotFoundAndStillProduceSpan() throws Exception {
        Capability capability = capabilityFromYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "POST"
                              name: "create-order"
                  consumes: []
                """.formatted(schemaVersion));

        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().get(0));

        // GET doesn't match any operation (only POST is defined)
        Request request = new Request(Method.GET, "http://localhost/orders");
        Response response = new Response(request);
        restlet.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_NOT_FOUND, response.getStatus());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Should still produce a server span on not-found");
        assertEquals("rest.request", spans.get(0).getName());
    }
}
