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
package io.ikanos.spec.observability;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Metrics collection and local exposure configuration.
 *
 * <h2>Thread safety</h2>
 * The {@code local} field is held in an {@link AtomicReference} so that future fluent
 * builders and Control-port runtime edits can replace the configuration atomically while
 * engine threads read it. Lazy initialization on {@link #getLocal()} uses
 * {@link AtomicReference#compareAndSet} to remain thread-safe. This satisfies SonarQube
 * rule {@code java:S3077}.
 */
public class ObservabilityMetricsSpec {

    private final AtomicReference<ObservabilityLocalEndpointSpec> local = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityLocalEndpointSpec getLocal() {
        ObservabilityLocalEndpointSpec current = local.get();
        if (current == null) {
            ObservabilityLocalEndpointSpec candidate = new ObservabilityLocalEndpointSpec();
            if (local.compareAndSet(null, candidate)) {
                return candidate;
            }
            return local.get();
        }
        return current;
    }

    public void setLocal(ObservabilityLocalEndpointSpec local) {
        this.local.set(local);
    }
}
