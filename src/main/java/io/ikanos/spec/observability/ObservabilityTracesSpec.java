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
 * Trace sampling, propagation, and local exposure configuration.
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference} so that fluent builders and
 * Control-port runtime edits can replace values atomically while engine threads read them.
 * The double {@code sampling} field is wrapped as {@code AtomicReference<Double>} for
 * consistency. This satisfies SonarQube rule {@code java:S3077}.
 */
public class ObservabilityTracesSpec {

    private final AtomicReference<Double> sampling = new AtomicReference<>(1.0);
    private final AtomicReference<String> propagation = new AtomicReference<>("w3c");
    private final AtomicReference<ObservabilityTracesLocalSpec> local = new AtomicReference<>();

    public double getSampling() {
        return sampling.get();
    }

    public void setSampling(double sampling) {
        this.sampling.set(sampling);
    }

    public String getPropagation() {
        return propagation.get();
    }

    public void setPropagation(String propagation) {
        this.propagation.set(propagation);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityTracesLocalSpec getLocal() {
        return local.get();
    }

    public void setLocal(ObservabilityTracesLocalSpec local) {
        this.local.set(local);
    }
}
