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
 * Trace sampling, propagation, and local exposure configuration.
 */
public class ObservabilityTracesSpec {

    private volatile double sampling = 1.0;
    private volatile String propagation = "w3c";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ObservabilityTracesLocalSpec local;

    public double getSampling() {
        return sampling;
    }

    public void setSampling(double sampling) {
        this.sampling = sampling;
    }

    public String getPropagation() {
        return propagation;
    }

    public void setPropagation(String propagation) {
        this.propagation = propagation;
    }

    public ObservabilityTracesLocalSpec getLocal() {
        return local;
    }

    public void setLocal(ObservabilityTracesLocalSpec local) {
        this.local = local;
    }
}
