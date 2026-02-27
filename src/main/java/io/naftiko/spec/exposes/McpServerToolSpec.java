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
package io.naftiko.spec.exposes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;

/**
 * MCP Tool Specification Element.
 * 
 * Defines an MCP tool that maps to consumed HTTP operations.
 * Supports both simple call mode (call + with) and full orchestration (steps + mappings).
 */
public class McpServerToolSpec {

    private volatile String name;

    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ApiServerCallSpec call;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<ApiServerStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public McpServerToolSpec() {
        this(null, null);
    }

    public McpServerToolSpec(String name, String description) {
        this.name = name;
        this.description = description;
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.steps = new CopyOnWriteArrayList<>();
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

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

    public ApiServerCallSpec getCall() {
        return call;
    }

    public void setCall(ApiServerCallSpec call) {
        this.call = call;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

    public List<ApiServerStepSpec> getSteps() {
        return steps;
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return outputParameters;
    }

}
