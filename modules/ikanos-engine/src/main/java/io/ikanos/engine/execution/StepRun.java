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
package io.ikanos.engine.execution;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.util.StepExecutionContext;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * State of one run of an {@link ExecutionTree} in the {@code none} mode: the parameters templates
 * resolve against, the step outputs, the last call result, and the exception that ended the run.
 * It lives for one request and is never shared.
 */
public final class StepRun {

    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final Map<String, Object> runtimeParameters = new HashMap<>();
    private final StepExecutionContext stepContext = new StepExecutionContext();
    private final Set<NodePath> evaluated = new HashSet<>();
    private final Map<NodePath, Object> answers = new HashMap<>();
    private ConsumedResult lastResult;
    private Exception failure;

    public StepRun(Map<String, Object> baseParameters) {
        if (baseParameters != null) {
            runtimeParameters.putAll(baseParameters);
        }
    }

    /** Parameters for template resolution, extended with each step's output. */
    public Map<String, Object> runtimeParameters() {
        return runtimeParameters;
    }

    /** Step outputs, for lookups and output mappings. */
    public StepExecutionContext stepContext() {
        return stepContext;
    }

    /** The result of the last call step, or {@code null}. */
    public ConsumedResult lastResult() {
        return lastResult;
    }

    public void setLastResult(ConsumedResult lastResult) {
        this.lastResult = lastResult;
    }

    /** The exception that ended the run, or {@code null}. */
    public Exception failure() {
        return failure;
    }

    void fail(Exception failure) {
        this.failure = failure;
    }

    /** The caller's answer to the {@code elicit} step at {@code path}, or {@code null}. */
    public Object answer(NodePath path) {
        return answers.get(path);
    }

    void setAnswer(NodePath path, Object answer) {
        answers.put(path, answer);
    }

    /**
     * Records the output of a step restored from the journal, as running it recorded it: in the
     * step context, and as a runtime parameter named after the step. A {@code null} or JSON
     * {@code null} output records nothing, like a step that produced no JSON output.
     */
    void restoreOutput(String stepName, Object output) {
        if (stepName == null || !(output instanceof JsonNode json) || json.isNull()
                || json.isMissingNode()) {
            return;
        }
        stepContext.storeStepOutput(stepName, json);
        runtimeParameters.put(stepName, MAPPER.convertValue(json, Object.class));
    }

    boolean isEvaluated(NodePath path) {
        return evaluated.contains(path);
    }

    void markEvaluated(NodePath path) {
        evaluated.add(path);
    }
}
