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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientToolSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Whether an exposed entry may ask its caller for input: its steps, the flow it references, or
 * the target of its singleton {@code call:}, hold an {@code elicit} step, or call a consumed MCP
 * tool declared {@code mayElicit: true}, whose questions pass through, directly or through flows
 * they call, at any depth. Only such an entry needs the machinery of input requests; one that
 * cannot ask runs as before.
 */
public final class Elicitation {

    private Elicitation() {
    }

    /**
     * @param ref the flow the entry references with {@code ref:}, or {@code null}
     * @param call the entry's singleton {@code call:}, used when it references no flow; may be
     *        {@code null}
     * @param steps the entry's own steps, used when it references no flow; may be {@code null}
     */
    public static boolean mayAsk(Capability capability, String ref, ServerCallSpec call,
            Map<String, OperationStepSpec> steps) {
        Set<AggregateFlow> seen = new HashSet<>();
        if (ref != null) {
            return mayAsk(capability, findFlow(capability, ref), seen);
        }
        return callAsks(capability, call, seen) || mayAsk(capability, steps, seen);
    }

    private static boolean mayAsk(Capability capability, AggregateFlow flow,
            Set<AggregateFlow> seen) {
        if (flow == null || !seen.add(flow)) {
            return false;
        }
        return callAsks(capability, flow.getCall(), seen)
                || mayAsk(capability, flow.getSteps(), seen);
    }

    /** Whether a singleton {@code call:} targets a tool or a flow that may ask. */
    private static boolean callAsks(Capability capability, ServerCallSpec call,
            Set<AggregateFlow> seen) {
        return call != null && call.getOperation() != null
                && targetAsks(capability, call.getOperation(), seen);
    }

    private static boolean targetAsks(Capability capability, String target,
            Set<AggregateFlow> seen) {
        return consumedToolAsks(capability, target)
                || mayAsk(capability, findFlow(capability, target), seen);
    }

    private static boolean mayAsk(Capability capability, Map<String, OperationStepSpec> steps,
            Set<AggregateFlow> seen) {
        if (steps == null) {
            return false;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepElicitSpec) {
                return true;
            }
            if (step instanceof OperationStepCallSpec call && call.getCall() != null
                    && targetAsks(capability, call.getCall(), seen)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code call} names a consumed MCP tool declared {@code mayElicit: true}. */
    public static boolean consumedToolAsks(Capability capability, String call) {
        int dot = call.indexOf('.');
        if (dot <= 0 || capability.getSpec() == null || capability.getSpec().getCapability() == null
                || capability.getSpec().getCapability().getConsumes() == null) {
            return false;
        }
        String namespace = call.substring(0, dot);
        String tool = call.substring(dot + 1);
        for (ClientSpec client : capability.getSpec().getCapability().getConsumes()) {
            if (client instanceof McpClientSpec mcp && namespace.equals(mcp.getNamespace())
                    && mcp.getTools() != null) {
                McpClientToolSpec spec = mcp.getTools().get(tool);
                return spec != null && spec.mayElicit();
            }
        }
        return false;
    }

    private static AggregateFlow findFlow(Capability capability, String ref) {
        try {
            return capability.lookupFlow(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
