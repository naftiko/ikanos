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

import io.naftiko.engine.telemetry.TelemetryBootstrap;
import io.opentelemetry.api.OpenTelemetry;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

/**
 * Prometheus metrics scrape endpoint. Bridges OTel-recorded metrics to Prometheus exposition
 * format. Returns {@code 503} when OTel is not active.
 *
 * <p>In Phase 1, this returns a basic {@code naftiko_up} gauge. Full metric bridging
 * (via {@code PrometheusMetricReader}) is wired when the OTel Prometheus exporter is on the
 * classpath.</p>
 */
public class MetricsResource extends ServerResource {

    @Get
    public Representation getMetrics() {
        OpenTelemetry otel = TelemetryBootstrap.get().getOpenTelemetry();
        if (otel == OpenTelemetry.noop()) {
            setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
            return new StringRepresentation(
                    "{\"error\":\"OpenTelemetry is not active. "
                            + "Metrics require the OTel SDK on the classpath.\"}",
                    MediaType.APPLICATION_JSON);
        }

        String metrics = "# HELP naftiko_up Capability liveness indicator\n"
                + "# TYPE naftiko_up gauge\n"
                + "naftiko_up 1\n";
        return new StringRepresentation(metrics, MediaType.TEXT_PLAIN);
    }
}
