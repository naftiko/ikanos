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
package io.naftiko.spec.exposes.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.naftiko.spec.observability.ObservabilitySpec;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Control Server Specification Element.
 *
 * <p>Defines a management adapter that provides engine-provided endpoints for health checks,
 * Prometheus metrics, trace inspection, and runtime diagnostics. Unlike business adapters, the
 * control port does not expose user-defined tools, operations, or skills.</p>
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ControlServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ControlManagementSpec management;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ObservabilitySpec observability;

    public ControlServerSpec() {
        this("localhost", 0);
    }

    public ControlServerSpec(String address, int port) {
        super("control", address, port);
    }

    public ControlManagementSpec getManagement() {
        if (management == null) {
            management = new ControlManagementSpec();
        }
        return management;
    }

    public void setManagement(ControlManagementSpec management) {
        this.management = management;
    }

    public ObservabilitySpec getObservability() {
        return observability;
    }

    public void setObservability(ObservabilitySpec observability) {
        this.observability = observability;
    }
}
