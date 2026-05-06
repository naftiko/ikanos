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
package io.naftiko.spec.exposes.rest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.naftiko.spec.exposes.ServerCallSpec;

/**
 * API Operation Step Specification Element.
 *
 * <p>Represents a step in an API operation workflow.
 * A step contains a call specification that defines which operation to invoke
 * and what parameters to pass to it.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}; the {@code with} parameter map is stored
 * as an immutable snapshot. This satisfies SonarQube rule {@code java:S3077}.
 */
public class RestServerStepSpec {

    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();

    public RestServerStepSpec() {
        this(null, null, null);
    }

    public RestServerStepSpec(ServerCallSpec call) {
        this(call, null, null);
    }

    public RestServerStepSpec(ServerCallSpec call, Map<String, Object> with) {
        this(call, with, null);
    }

    public RestServerStepSpec(ServerCallSpec call, Map<String, Object> with, String description) {
        this.call.set(call);
        this.with.set(with != null ? Map.copyOf(with) : null);
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ServerCallSpec getCall() {
        return call.get();
    }

    public void setCall(ServerCallSpec call) {
        this.call.set(call);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() {
        return with.get();
    }

    public void setWith(Map<String, Object> with) {
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

}

