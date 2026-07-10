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

import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import io.ikanos.engine.observability.TelemetryBootstrap;

/**
 * Prometheus metrics scrape endpoint. Bridges OTel-recorded metrics to Prometheus exposition
 * format via {@link TelemetryBootstrap#collectPrometheusMetrics()}. Returns {@code 503} when
 * OTel is not active.
 */
public class MetricsResource extends ServerResource {

    @Get
    public Representation getMetrics() {
        String prometheusText = TelemetryBootstrap.get().collectPrometheusMetrics();
        if (prometheusText == null) {
            setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
            return new StringRepresentation(
                    "{\"error\":\"OpenTelemetry is not active. "
                            + "Metrics require the OTel SDK on the classpath.\"}",
                    MediaType.APPLICATION_JSON);
        }

        return new StringRepresentation(prometheusText, MediaType.TEXT_PLAIN);
    }
}
