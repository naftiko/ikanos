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
package io.ikanos.spec.exposes.control;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.observability.ObservabilitySpec;

/**
 * Control Server Specification Element.
 *
 * <p>Defines a management adapter that provides engine-provided endpoints for health checks,
 * Prometheus metrics, trace inspection, and runtime diagnostics. Unlike business adapters, the
 * control port does not expose user-defined tools, operations, or skills.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}; lazy initialization of {@code management}
 * uses {@link AtomicReference#compareAndSet} to remain thread-safe. This satisfies SonarQube
 * rule {@code java:S3077}.
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class ControlServerSpec extends ServerSpec {

    private final AtomicReference<ControlManagementSpec> management = new AtomicReference<>();
    private final AtomicReference<ObservabilitySpec> observability = new AtomicReference<>();

    public ControlServerSpec() {
        this("localhost", 0);
    }

    public ControlServerSpec(String address, int port) {
        super("control", address, port);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ControlManagementSpec getManagement() {
        ControlManagementSpec current = management.get();
        if (current == null) {
            ControlManagementSpec candidate = new ControlManagementSpec();
            if (management.compareAndSet(null, candidate)) {
                return candidate;
            }
            return management.get();
        }
        return current;
    }

    public void setManagement(ControlManagementSpec management) {
        this.management.set(management);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilitySpec getObservability() {
        return observability.get();
    }

    public void setObservability(ObservabilitySpec observability) {
        this.observability.set(observability);
    }
}
