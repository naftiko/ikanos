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
package io.naftiko.spec.observability;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Spec-driven observability configuration. All fields are optional — defaults to OTel env vars
 * when not specified.
 *
 * <h2>Thread safety</h2>
 * Each field is held in an atomic container ({@link AtomicReference} or {@link AtomicBoolean})
 * so that fluent builders and Control-port runtime edits can replace values atomically while
 * engine threads read them. This satisfies SonarQube rule {@code java:S3077}.
 */
public class ObservabilitySpec {

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<ObservabilityMetricsSpec> metrics = new AtomicReference<>();
    private final AtomicReference<ObservabilityTracesSpec> traces = new AtomicReference<>();
    private final AtomicReference<ObservabilityExportersSpec> exporters = new AtomicReference<>();

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityMetricsSpec getMetrics() {
        return metrics.get();
    }

    public void setMetrics(ObservabilityMetricsSpec metrics) {
        this.metrics.set(metrics);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityTracesSpec getTraces() {
        return traces.get();
    }

    public void setTraces(ObservabilityTracesSpec traces) {
        this.traces.set(traces);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityExportersSpec getExporters() {
        return exporters.get();
    }

    public void setExporters(ObservabilityExportersSpec exporters) {
        this.exporters.set(exporters);
    }
}
