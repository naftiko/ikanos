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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.rest.ResourceRestlet;
import io.ikanos.engine.observability.OtelTestFixtures;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * An exception that escapes {@link ResourceRestlet} is answered by {@link UncaughtExceptionFilter}
 * once the request's server span has ended. With telemetry on, the reference the caller gets must
 * be that span's trace id, which {@code ResourceRestlet} keeps on the request
 * ({@link ErrorReference#rememberTrace}), so the reference finds the trace (issue #897).
 */
class UncaughtExceptionTraceIntegrationTest {

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
    }

    @Test
    void referenceShouldBeTheServerSpanTraceIdWhenAnExceptionEscapesResourceRestlet()
            throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
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
                """.formatted(VersionHelper.getSchemaVersion()), IkanosSpec.class);
        Capability capability = new Capability(spec);
        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        // A resource whose operations cannot be read once the restlet is built: the failure
        // happens inside the request's span, after ResourceRestlet started it, and escapes the
        // restlet. The constructor reads them too, so they only fail from the request on.
        AtomicBoolean broken = new AtomicBoolean();
        RestServerResourceSpec failing = new RestServerResourceSpec("/orders") {
            @Override
            public Map<String, RestServerOperationSpec> getOperations() {
                if (broken.get()) {
                    throw new IllegalStateException("operations unavailable");
                }
                return super.getOperations();
            }
        };
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec, failing);
        broken.set(true);

        Request request = new Request(Method.GET, "http://localhost/orders");
        Response response = new Response(request);
        new UncaughtExceptionFilter(restlet).handle(request, response);

        assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
        List<SpanData> serverSpans = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .toList();
        assertEquals(1, serverSpans.size(), "the request should produce one server span");
        String traceId = serverSpans.get(0).getTraceId();
        String body = response.getEntity().getText();
        assertTrue(body.endsWith("(reference: " + traceId + ")"),
                "the reference should be the server span's trace id " + traceId + ": " + body);
    }
}
