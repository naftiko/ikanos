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
package io.ikanos.engine.aggregates;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.aggregates.SemanticsSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Runtime-executable wrapper around an {@link AggregateFlowSpec}.
 *
 * <p>Holds the spec data (from YAML) plus the {@link OperationStepExecutor} needed to actually
 * run the flow. Adapters (MCP tools, REST operations) that reference an aggregate flow
 * delegate execution here instead of duplicating the flow's fields.</p>
 */
public class AggregateFlow {

    /** Default limit on how many flows may be active on one call chain, including the first. */
    public static final int DEFAULT_MAX_CALL_DEPTH = 8;

    /**
     * Qualified names of the flows executing on the current thread, outermost first. Flows that
     * call flows run on the caller's thread, so this is the call chain.
     */
    private static final ThreadLocal<Deque<String>> CALL_CHAIN = new ThreadLocal<>();

    private final AggregateFlowSpec spec;
    private final OperationStepExecutor stepExecutor;
    private final String namespace;
    private final int maxCallDepth;

    AggregateFlow(AggregateFlowSpec spec, OperationStepExecutor stepExecutor,
            String namespace) {
        this(spec, stepExecutor, namespace, DEFAULT_MAX_CALL_DEPTH);
    }

    AggregateFlow(AggregateFlowSpec spec, OperationStepExecutor stepExecutor,
            String namespace, int maxCallDepth) {
        this.spec = spec;
        this.stepExecutor = stepExecutor;
        this.namespace = namespace;
        this.maxCallDepth = maxCallDepth;
    }

    public String getName() {
        return spec.getName();
    }

    /** @return the namespace of the aggregate that owns this flow */
    public String getNamespace() {
        return namespace;
    }

    /** @return the qualified name of this flow, {@code namespace.flow-name} */
    public String getRef() {
        return namespace + "." + spec.getName();
    }

    /** @return the limit on how many flows may be active on one call chain */
    public int getMaxCallDepth() {
        return maxCallDepth;
    }

    public String getDescription() {
        return spec.getDescription();
    }

    public SemanticsSpec getSemantics() {
        return spec.getSemantics();
    }

    public List<InputParameterSpec> getInputParameters() {
        Map<String, InputParameterSpec> params = spec.getInputParameters();
        return params != null ? List.copyOf(params.values()) : List.of();
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

    public Map<String, OperationStepSpec> getSteps() {
        return spec.getSteps();
    }

    public List<StepOutputMappingSpec> getMappings() {
        return spec.getMappings();
    }

    /**
     * Execute this aggregate flow with the given parameters.
     *
     * <p>Supports three modes:
     * <ol>
     *   <li><b>Mock</b> — no call, no steps: builds output from {@code outputParameters} values</li>
     *   <li><b>Orchestrated</b> — steps defined: runs step sequence + optional mappings</li>
     *   <li><b>Simple call</b> — call defined: one consumed operation, or another flow</li>
     * </ol>
     *
     * <p>A flow may be called by another flow ({@code call: namespace.flow-name}). The flows
     * active on the current thread form a call chain; when it would exceed
     * {@link #getMaxCallDepth()}, a {@link FlowCallDepthExceededException} naming the chain is
     * thrown before this flow runs.</p>
     *
     * @param parameters resolved input parameters (merged with adapter-level 'with')
     * @return a transport-neutral {@link FlowResult}
     */
    public FlowResult execute(Map<String, Object> parameters) throws Exception {
        String ref = getRef();
        Deque<String> chain = CALL_CHAIN.get();
        if (chain == null) {
            chain = new ArrayDeque<>();
            CALL_CHAIN.set(chain);
        }
        if (chain.size() >= maxCallDepth) {
            List<String> names = new ArrayList<>(chain);
            names.add(ref);
            if (chain.isEmpty()) {
                CALL_CHAIN.remove();
            }
            throw new FlowCallDepthExceededException(names, maxCallDepth);
        }
        chain.addLast(ref);
        Span span = TelemetryBootstrap.get().startAggregateFlowSpan(ref);
        try (Scope scope = span.makeCurrent()) {
            return doExecute(parameters);
        } catch (Exception e) {
            TelemetryBootstrap.recordError(span, e);
            throw e;
        } finally {
            TelemetryBootstrap.endSpan(span);
            chain.removeLast();
            if (chain.isEmpty()) {
                CALL_CHAIN.remove();
            }
        }
    }

    FlowResult doExecute(Map<String, Object> parameters) throws Exception {
        Map<String, Object> merged = new HashMap<>();
        if (parameters != null) {
            merged.putAll(parameters);
        }

        // Merge flow-level 'with' parameters
        OperationStepExecutor.mergeWithParameters(spec.getWith(), merged, namespace);

        boolean hasCall = spec.getCall() != null;
        boolean isOrchestrated = spec.getSteps() != null && !spec.getSteps().isEmpty();

        // Mock mode
        if (!hasCall && !isOrchestrated) {
            ObjectMapper mapper = IkanosMappers.json();
            JsonNode mockRoot = Resolver.buildMockData(spec.getOutputParameters(), mapper, merged);
            return new FlowResult(null, null, mockRoot);
        }

        // The executor is shared by every aggregate flow, so this flow's namespace is passed with
        // each call rather than stored on the executor (#320).

        // Orchestrated mode
        if (isOrchestrated) {
            OperationStepExecutor.StepExecutionResult stepResult =
                    stepExecutor.executeSteps(spec.getSteps(), merged, namespace);

            if (spec.getMappings() != null && !spec.getMappings().isEmpty()) {
                String mapped = stepExecutor.resolveStepMappings(
                        spec.getMappings(), spec.getOutputParameters(), stepResult.stepContext);
                if (mapped != null) {
                    return new FlowResult(stepResult.lastResult, mapped, null);
                }
            }

            return new FlowResult(stepResult.lastResult, null, null);
        }

        // Simple call mode
        ConsumedResult found =
                stepExecutor.execute(spec.getCall(), spec.getSteps(), merged,
                        "Flow '" + spec.getName() + "'", namespace);

        // Apply output parameter mappings if defined on the flow
        if (spec.getOutputParameters() != null && !spec.getOutputParameters().isEmpty()
                && found != null && found.hasBody()) {
            String mapped = stepExecutor.mapResult(found,
                    spec.getOutputParameters());
            if (mapped != null) {
                return new FlowResult(found, mapped, null);
            }
        }

        return new FlowResult(found, null, null);
    }

}
