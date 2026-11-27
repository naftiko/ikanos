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

import java.util.List;
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
    private final AtomicReference<List<String>> successCriteria = new AtomicReference<>();

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

    /**
     * @return what the step calls, written {@code what:}: a consumed operation or an aggregate
     *         flow, as {@code <namespace>.<name>}
     */
    @JsonProperty("what")
    public String getCall() {
        return call.get();
    }

    @JsonProperty("what")
    public void setCall(String call) {
        this.call.set(call);
    }

    /**
     * @return the conditions under which the call's result counts as a success
     *         ({@code successCriteria:}), any one of which is enough; {@code null} when the step
     *         succeeds on a {@code 2xx} answer only
     */
    @JsonProperty("successCriteria")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> getSuccessCriteria() {
        return successCriteria.get();
    }

    @JsonProperty("successCriteria")
    public void setSuccessCriteria(List<String> successCriteria) {
        this.successCriteria.set(successCriteria != null ? List.copyOf(successCriteria) : null);
    }

    /**
     * Refuses {@code call:}, the name of {@code what:} before steps became a list, rather than let
     * the loader drop it as an unknown property and run a step that calls nothing.
     */
    @JsonProperty(value = "call", access = JsonProperty.Access.WRITE_ONLY)
    void refuseCall(Object ignored) {
        String step = getName() != null ? "Step '" + getName() + "'" : "A call step";
        throw new IllegalArgumentException(step
                + " names what it calls with 'call:', which is now 'what:'. "
                + OperationStepMapDeserializer.MIGRATE_HINT);
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
