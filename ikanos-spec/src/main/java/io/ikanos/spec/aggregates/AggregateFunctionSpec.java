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
package io.ikanos.spec.aggregates;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

/**
 * Aggregate Function Specification Element.
 * 
 * <p>A reusable invocable unit within an aggregate. Adapter units reference it via
 * {@code ref: aggregate-namespace.function-name}.</p>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot inside an {@link AtomicReference} so that fluent builders and
 * Control-port runtime edits can replace it atomically. List fields use {@link CopyOnWriteArrayList}.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class AggregateFunctionSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<SemanticsSpec> semantics = new AtomicReference<>();
    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<StepOutputMappingSpec> mappings;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public AggregateFunctionSpec() {
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.steps = new CopyOnWriteArrayList<>();
        this.mappings = new CopyOnWriteArrayList<>();
        this.outputParameters = new CopyOnWriteArrayList<>();
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public SemanticsSpec getSemantics() {
        return semantics.get();
    }

    public void setSemantics(SemanticsSpec semantics) {
        this.semantics.set(semantics);
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
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

    public List<OperationStepSpec> getSteps() {
        return steps;
    }

    public List<StepOutputMappingSpec> getMappings() {
        return mappings;
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return outputParameters;
    }

}
