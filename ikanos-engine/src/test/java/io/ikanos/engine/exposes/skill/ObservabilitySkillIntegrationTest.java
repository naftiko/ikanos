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
package io.ikanos.engine.exposes.skill;

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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Integration tests verifying that Skill adapter requests produce the expected OTel server spans,
 * including W3C traceparent extraction.
 */
public class ObservabilitySkillIntegrationTest {

    private static final int SKILL_PORT = 9098;
    private static final String BASE_URL = "http://localhost:" + SKILL_PORT;

    private static final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private static SdkTracerProvider tracerProvider;
    private static SkillServerAdapter skillAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        tracerProvider = buildTracerProvider();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(currentTracerProvider())
                .setPropagators(contextPropagators())
                .build();
        TelemetryBootstrap.init(sdk);

        File file = new File("src/test/resources/skill-capability.yaml");
        assertTrue(file.exists());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        // Override the port to avoid conflict with other tests
        spec.getCapability().getExposes().stream()
                .filter(s -> "skill".equals(s.getType()))
                .findFirst()
                .ifPresent(s -> s.setPort(SKILL_PORT));

        Capability capability = new Capability(spec);

        skillAdapter = (SkillServerAdapter) capability.getServerAdapters().stream()
                .filter(a -> a instanceof SkillServerAdapter)
                .findFirst()
                .orElseThrow();

        skillAdapter.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (skillAdapter != null) {
            skillAdapter.stop();
        }
        TelemetryBootstrap.reset();
        exporter.reset();
    }

    @Test
    void skillRequestShouldProduceServerSpan() throws Exception {
        exporter.reset();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        currentTracerProvider().forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertFalse(spans.isEmpty(), "Should produce at least one span");

        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("skill.request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing skill.request span"));

        assertEquals(SpanKind.SERVER, serverSpan.getKind());
        assertEquals("skill",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_ADAPTER_TYPE));
        assertEquals("GET",
                stringAttribute(serverSpan.getAttributes(), TelemetryBootstrap.ATTR_HTTP_METHOD));
    }

    @Test
    void skillRequestShouldExtractInboundTraceparent() throws Exception {
        exporter.reset();

        String traceparent = "00-abcdef1234567890abcdef1234567890-1234567890abcdef-01";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills"))
                .header("traceparent", traceparent)
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        currentTracerProvider().forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData serverSpan = spans.stream()
                .filter(s -> s.getName().equals("skill.request"))
                .findFirst()
                .orElseThrow();

        assertEquals("abcdef1234567890abcdef1234567890", serverSpan.getTraceId(),
                "Server span should continue the inbound trace");
        assertEquals("1234567890abcdef", serverSpan.getParentSpanId(),
                "Server span parent should be the inbound span");
    }

        @Nonnull
        private static SdkTracerProvider buildTracerProvider() {
                return java.util.Objects.requireNonNull(SdkTracerProvider.builder()
                                .addSpanProcessor(spanProcessor())
                                .build());
        }

        @Nonnull
        private static SdkTracerProvider currentTracerProvider() {
                return java.util.Objects.requireNonNull(tracerProvider);
        }

        @Nonnull
        private static io.opentelemetry.sdk.trace.SpanProcessor spanProcessor() {
                return java.util.Objects.requireNonNull(SimpleSpanProcessor.create(spanExporter()));
        }

        @Nonnull
        private static io.opentelemetry.sdk.trace.export.SpanExporter spanExporter() {
                return java.util.Objects.requireNonNull(exporter);
        }

        @Nonnull
        private static ContextPropagators contextPropagators() {
                return java.util.Objects.requireNonNull(ContextPropagators.create(textMapPropagator()));
        }

        @Nonnull
        private static TextMapPropagator textMapPropagator() {
                return java.util.Objects.requireNonNull(W3CTraceContextPropagator.getInstance());
        }

        private static String stringAttribute(Attributes attributes, AttributeKey<String> key) {
                return attributes.get(nonNullStringKey(key));
        }

        @Nonnull
        private static AttributeKey<String> nonNullStringKey(AttributeKey<String> key) {
                return java.util.Objects.requireNonNull(key);
        }
}
