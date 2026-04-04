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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.naftiko.spec.exposes.OperationStepSpec;
import io.naftiko.spec.exposes.ServerCallSpec;

/**
 * Aggregate Function Specification Element.
 * 
 * A reusable invocable unit within an aggregate. Adapter units reference it via
 * ref: aggregate-namespace.function-name.
 */
public class AggregateFunctionSpec {

    private volatile String name;
    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile SemanticsSpec semantics;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ServerCallSpec call;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<Map<String, Object>> mappings;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public AggregateFunctionSpec() {
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.steps = new CopyOnWriteArrayList<>();
        this.mappings = new CopyOnWriteArrayList<>();
        this.outputParameters = new CopyOnWriteArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SemanticsSpec getSemantics() {
        return semantics;
    }

    public void setSemantics(SemanticsSpec semantics) {
        this.semantics = semantics;
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

    public ServerCallSpec getCall() {
        return call;
    }

    public void setCall(ServerCallSpec call) {
        this.call = call;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

    public List<OperationStepSpec> getSteps() {
        return steps;
    }

    public List<Map<String, Object>> getMappings() {
        return mappings;
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return outputParameters;
    }

}
