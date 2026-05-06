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
package io.naftiko.spec.exposes.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.util.OperationStepSpec;
import io.naftiko.spec.util.StepOutputMappingSpec;

/**
 * MCP Tool Specification Element.
 *
 * <p>Defines an MCP tool that maps to consumed HTTP operations.
 * Supports both simple call mode (call + with) and full orchestration (steps + mappings).</p>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot. List fields use {@link CopyOnWriteArrayList}. This
 * satisfies SonarQube rule {@code java:S3077}.
 */
public class McpServerToolSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> label = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> ref = new AtomicReference<>();
    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<McpToolHintsSpec> hints = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<StepOutputMappingSpec> mappings;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public McpServerToolSpec() {
        this(null, null, null);
    }

    public McpServerToolSpec(String name, String label, String description) {
        this.name.set(name);
        this.label.set(label);
        this.description.set(description);
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLabel() {
        return label.get();
    }

    public void setLabel(String label) {
        this.label.set(label);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public McpToolHintsSpec getHints() {
        return hints.get();
    }

    public void setHints(McpToolHintsSpec hints) {
        this.hints.set(hints);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getRef() {
        return ref.get();
    }

    public void setRef(String ref) {
        this.ref.set(ref);
    }

}
