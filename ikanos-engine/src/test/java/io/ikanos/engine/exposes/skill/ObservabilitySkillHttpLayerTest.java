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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.representation.Representation;
import org.slf4j.MDC;

/**
 * Regression test for issue #548 (review completeness, PR #553).
 *
 * <p>The skill server adapter ({@link SkillServerResource#handle()}) creates a {@code SERVER}
 * span and makes it current exactly like the REST and MCP adapters. For log-trace correlation to
 * work, the Logback MDC must contain non-empty {@code trace_id} and {@code span_id} keys while the
 * span is current — which requires {@link TelemetryBootstrap#populateMdc} to be called within the
 * span scope and {@link TelemetryBootstrap#clearMdc} in the paired {@code finally} block.</p>
 *
 * <p>Before the fix, {@code SkillServerResource.handle()} omitted that pairing, so skill request
 * log lines always had empty {@code %X{trace_id}} / {@code %X{span_id}} — the same defect #548
 * fixed for REST and MCP.</p>
 */
class ObservabilitySkillHttpLayerTest {

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

    private static String skillCapabilityYaml() {
        return """
                ikanos: "%s"
                info:
                  display: "skill-capability"
                capability:
                  exposes:
                    - type: "skill"
                      address: "localhost"
                      port: 0
                      namespace: "orders-skills"
                      description: "Order management skills catalog"
                      skills:
                        onboarding-guide:
                          description: "A purely descriptive skill with no tools"
                  consumes: []
                """.formatted(SCHEMA_VERSION);
    }

    /**
     * Builds the {@link Context} that {@link SkillServerAdapter} attaches to each
     * {@link SkillServerResource}, without starting a real HTTP server.
     */
    private Context skillContext(Capability capability) {
        SkillServerAdapter adapter = (SkillServerAdapter) capability.getServerAdapters().stream()
                .filter(a -> a instanceof SkillServerAdapter)
                .findFirst()
                .orElseThrow();
        SkillServerSpec serverSpec = adapter.getSkillServerSpec();

        Context ctx = new Context();
        ctx.getAttributes().put("skillServerSpec", serverSpec);
        ctx.getAttributes().put("capabilityName", "skill-capability");
        return ctx;
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void skillRequestShouldPopulateMdcTraceIdAndSpanIdDuringExecution() throws Exception {
        Capability capability = capabilityFromYaml(skillCapabilityYaml());
        Context ctx = skillContext(capability);

        // Observe the MDC mid-request, while the SERVER span created in
        // SkillServerResource.handle() is current. CatalogResource.getCatalog() is the @Get
        // handler invoked by super.handle() within that scope, so overriding it captures the
        // MDC without reflection.
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedSpanId = new AtomicReference<>();

        CatalogResource resource = new CatalogResource() {
            @Override
            public Representation getCatalog() {
                capturedTraceId.set(MDC.get(TelemetryBootstrap.MDC_TRACE_ID));
                capturedSpanId.set(MDC.get(TelemetryBootstrap.MDC_SPAN_ID));
                return super.getCatalog();
            }
        };
        Request request = new Request(Method.GET, "http://localhost/skills");
        Response response = new Response(request);
        resource.init(ctx, request, response);

        resource.handle();

        assertNotNull(capturedTraceId.get(),
                "MDC trace_id must be non-null inside the span scope (issue #548: "
                + "populateMdc() not called within the span scope)");
        assertFalse(capturedTraceId.get().isBlank(),
                "MDC trace_id must not be blank inside the span scope — was: '"
                + capturedTraceId.get() + "'");
        assertNotNull(capturedSpanId.get(),
                "MDC span_id must be non-null inside the span scope (issue #548)");
        assertFalse(capturedSpanId.get().isBlank(),
                "MDC span_id must not be blank inside the span scope — was: '"
                + capturedSpanId.get() + "'");
    }

    @Test
    void skillRequestShouldClearMdcAfterRequestCompletes() throws Exception {
        Capability capability = capabilityFromYaml(skillCapabilityYaml());
        Context ctx = skillContext(capability);

        CatalogResource resource = new CatalogResource();
        Request request = new Request(Method.GET, "http://localhost/skills");
        Response response = new Response(request);
        resource.init(ctx, request, response);

        resource.handle();

        // The finally block in SkillServerResource.handle() must clear the MDC keys once the
        // span scope exits, so no trace context leaks into unrelated log lines on the thread.
        String traceIdAfter = MDC.get(TelemetryBootstrap.MDC_TRACE_ID);
        String spanIdAfter = MDC.get(TelemetryBootstrap.MDC_SPAN_ID);

        assertTrue(traceIdAfter == null || traceIdAfter.isBlank(),
                "MDC trace_id must be blank after the span scope ends — was: '" + traceIdAfter + "'");
        assertTrue(spanIdAfter == null || spanIdAfter.isBlank(),
                "MDC span_id must be blank after the span scope ends — was: '" + spanIdAfter + "'");
    }
}
