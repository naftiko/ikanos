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
package io.naftiko.engine.aggregates;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.engine.util.Resolver;
import io.naftiko.spec.AggregateFunctionSpec;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.SemanticsSpec;
import io.naftiko.spec.exposes.OperationStepSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.exposes.StepOutputMappingSpec;

/**
 * Runtime-executable wrapper around an {@link AggregateFunctionSpec}.
 *
 * <p>Holds the spec data (from YAML) plus the {@link OperationStepExecutor} needed to actually
 * run the function. Adapters (MCP tools, REST operations) that reference an aggregate function
 * delegate execution here instead of duplicating the function's fields.</p>
 */
public class AggregateFunction {

    private final AggregateFunctionSpec spec;
    private final OperationStepExecutor stepExecutor;

    AggregateFunction(AggregateFunctionSpec spec, OperationStepExecutor stepExecutor) {
        this.spec = spec;
        this.stepExecutor = stepExecutor;
    }

    public String getName() {
        return spec.getName();
    }

    public String getDescription() {
        return spec.getDescription();
    }

    public SemanticsSpec getSemantics() {
        return spec.getSemantics();
    }

    public List<InputParameterSpec> getInputParameters() {
        return spec.getInputParameters();
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return spec.getOutputParameters();
    }

    public ServerCallSpec getCall() {
        return spec.getCall();
    }

    public Map<String, Object> getWith() {
        return spec.getWith();
    }

    public List<OperationStepSpec> getSteps() {
        return spec.getSteps();
    }

    public List<StepOutputMappingSpec> getMappings() {
        return spec.getMappings();
    }

    /**
     * Execute this aggregate function with the given parameters.
     *
     * <p>Supports three modes:
     * <ol>
     *   <li><b>Mock</b> — no call, no steps: builds output from {@code outputParameters} values</li>
     *   <li><b>Orchestrated</b> — steps defined: runs step sequence + optional mappings</li>
     *   <li><b>Simple call</b> — call defined: single HTTP dispatch</li>
     * </ol>
     *
     * @param parameters resolved input parameters (merged with adapter-level 'with')
     * @return a transport-neutral {@link FunctionResult}
     */
    public FunctionResult execute(Map<String, Object> parameters) throws Exception {
        Map<String, Object> merged = new HashMap<>();
        if (parameters != null) {
            merged.putAll(parameters);
        }

        // Merge function-level 'with' parameters
        OperationStepExecutor.mergeWithParameters(spec.getWith(), merged, null);

        boolean hasCall = spec.getCall() != null;
        boolean isOrchestrated = spec.getSteps() != null && !spec.getSteps().isEmpty();

        // Mock mode
        if (!hasCall && !isOrchestrated) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode mockRoot = Resolver.buildMockData(spec.getOutputParameters(), mapper, merged);
            return new FunctionResult(null, null, mockRoot);
        }

        // Orchestrated mode
        if (isOrchestrated) {
            OperationStepExecutor.StepExecutionResult stepResult =
                    stepExecutor.executeSteps(spec.getSteps(), merged);

            if (spec.getMappings() != null && !spec.getMappings().isEmpty()) {
                String mapped = stepExecutor.resolveStepMappings(
                        spec.getMappings(), stepResult.stepContext);
                if (mapped != null) {
                    return new FunctionResult(stepResult.lastContext, mapped, null);
                }
            }

            return new FunctionResult(stepResult.lastContext, null, null);
        }

        // Simple call mode
        OperationStepExecutor.HandlingContext found =
                stepExecutor.execute(spec.getCall(), spec.getSteps(), merged,
                        "Function '" + spec.getName() + "'");

        // Apply output parameter mappings if defined on the function
        if (spec.getOutputParameters() != null && !spec.getOutputParameters().isEmpty()
                && found != null && found.clientResponse != null
                && found.clientResponse.getEntity() != null) {
            String responseText = found.clientResponse.getEntity().getText();
            String mapped = stepExecutor.applyOutputMappings(responseText,
                    spec.getOutputParameters());
            if (mapped != null) {
                return new FunctionResult(found, mapped, null);
            }
        }

        return new FunctionResult(found, null, null);
    }

}
