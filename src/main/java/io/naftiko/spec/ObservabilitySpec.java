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
package io.naftiko.spec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Spec-driven observability configuration. All fields are optional — defaults to OTel env vars
 * when not specified.
 */
public class ObservabilitySpec {

    private volatile boolean enabled = true;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ObservabilityTracesSpec traces;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ObservabilityExportersSpec exporters;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ObservabilityTracesSpec getTraces() {
        return traces;
    }

    public void setTraces(ObservabilityTracesSpec traces) {
        this.traces = traces;
    }

    public ObservabilityExportersSpec getExporters() {
        return exporters;
    }

    public void setExporters(ObservabilityExportersSpec exporters) {
        this.exporters = exporters;
    }
}
