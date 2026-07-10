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
package io.ikanos.spec.util;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Operation Step Call Specification Element.
 *
 * <p>Represents a call to a consumed operation within an orchestration step.
 * Includes the operation reference and optional parameter injection via WithInjector.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}; the {@code with} parameter map is stored
 * as an immutable snapshot. This satisfies SonarQube rule {@code java:S3077}.
 */
public class OperationStepCallSpec extends OperationStepSpec {

    private final AtomicReference<String> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();

    public OperationStepCallSpec() {
        this(null, null, null, null);
    }

    public OperationStepCallSpec(String name, String call) {
        this(name, call, null);
    }

    public OperationStepCallSpec(String name, String call, Map<String, Object> with) {
        this("call", name, call, with);
    }

    public OperationStepCallSpec(String type, String name, String call, Map<String, Object> with) {
        super(type, name);
        this.call.set(call);
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    @JsonProperty("call")
    public String getCall() {
        return call.get();
    }

    @JsonProperty("call")
    public void setCall(String call) {
        this.call.set(call);
    }

    @JsonProperty("with")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() {
        return with.get();
    }

    @JsonProperty("with")
    public void setWith(Map<String, Object> with) {
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

}
