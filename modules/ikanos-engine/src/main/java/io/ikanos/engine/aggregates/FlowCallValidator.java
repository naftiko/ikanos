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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.ikanos.engine.util.QualifiedName;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Load-time checks for calls between aggregate flows.
 *
 * <p>A {@code call} step, or a flow-level {@code call:}, may name another aggregate flow. This
 * validator runs once the imports are resolved and fails startup when:</p>
 * <ul>
 *   <li>a call names an aggregate namespace but no flow of that name;</li>
 *   <li>flows call each other in a cycle (the message lists the cycle);</li>
 *   <li>an exposed unit (MCP tool or resource, REST operation) names a flow in its own
 *       {@code call:}. Exposed units reach flows through {@code ref:}, which also derives their
 *       description, hints, and input and output schemas from the flow.</li>
 * </ul>
 */
public final class FlowCallValidator {

    private FlowCallValidator() {
    }

    /**
     * Validate flow calls in the given spec.
     *
     * @param spec the resolved spec
     * @throws IllegalArgumentException when a check fails
     */
    public static void validate(IkanosSpec spec) {
        CapabilitySpec capability = spec != null ? spec.getCapability() : null;
        if (capability == null || capability.getAggregates() == null
                || capability.getAggregates().isEmpty()) {
            return;
        }

        Map<String, Set<String>> flowsByNamespace = new HashMap<>();
        Map<String, AggregateFlowSpec> flows = new LinkedHashMap<>();
        for (AggregateSpec aggregate : capability.getAggregates().values()) {
            if (aggregate == null || aggregate.getNamespace() == null) {
                continue;
            }
            Set<String> names = flowsByNamespace.computeIfAbsent(aggregate.getNamespace(),
                    k -> new LinkedHashSet<>());
            for (AggregateFlowSpec flow : aggregate.getFlows().values()) {
                names.add(flow.getName());
                flows.put(aggregate.getNamespace() + "." + flow.getName(), flow);
            }
        }

        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (Map.Entry<String, AggregateFlowSpec> entry : flows.entrySet()) {
            List<String> targets = new ArrayList<>();
            for (String call : callsOf(entry.getValue())) {
                QualifiedName target = QualifiedName.parse(call);
                if (target == null || !flowsByNamespace.containsKey(target.namespace())) {
                    continue;
                }
                if (!flowsByNamespace.get(target.namespace()).contains(target.name())) {
                    throw new IllegalArgumentException("Flow '" + entry.getKey() + "' calls '"
                            + call + "', but aggregate '" + target.namespace()
                            + "' has no flow '" + target.name() + "'");
                }
                targets.add(target.toString());
            }
            graph.put(entry.getKey(), targets);
        }

        detectCycles(graph);
        rejectExposedCallsToFlows(capability, flowsByNamespace);
    }

    /** @return the {@code call} targets of a flow: its own {@code call:} and its call steps */
    static List<String> callsOf(AggregateFlowSpec flow) {
        List<String> calls = new ArrayList<>();
        if (flow.getCall() != null && flow.getCall().getOperation() != null) {
            calls.add(flow.getCall().getOperation());
        }
        addStepCalls(calls, flow.getSteps());
        return calls;
    }

    private static void addStepCalls(List<String> calls, Map<String, OperationStepSpec> steps) {
        if (steps == null) {
            return;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepCallSpec callStep && callStep.getCall() != null) {
                calls.add(callStep.getCall());
            }
        }
    }

    private static void detectCycles(Map<String, List<String>> graph) {
        Set<String> done = new LinkedHashSet<>();
        for (String start : graph.keySet()) {
            visit(start, graph, new ArrayList<>(), done);
        }
    }

    private static void visit(String node, Map<String, List<String>> graph, List<String> path,
            Set<String> done) {
        if (done.contains(node)) {
            return;
        }
        int index = path.indexOf(node);
        if (index >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(index, path.size()));
            cycle.add(node);
            throw new IllegalArgumentException(
                    "Aggregate flows call each other in a cycle: " + String.join(" -> ", cycle));
        }
        path.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            visit(next, graph, path, done);
        }
        path.remove(path.size() - 1);
        done.add(node);
    }

    private static void rejectExposedCallsToFlows(CapabilitySpec capability,
            Map<String, Set<String>> flowsByNamespace) {
        List<ServerSpec> exposes = capability.getExposes();
        if (exposes == null) {
            return;
        }
        for (ServerSpec server : exposes) {
            if (server instanceof McpServerSpec mcp) {
                for (McpServerToolSpec tool : mcp.getTools().values()) {
                    rejectIfFlow(tool.getCall(), "MCP tool '" + tool.getName() + "'",
                            flowsByNamespace);
                }
                for (McpServerResourceSpec resource : mcp.getResources().values()) {
                    rejectIfFlow(resource.getCall(), "MCP resource '" + resource.getName() + "'",
                            flowsByNamespace);
                }
            } else if (server instanceof RestServerSpec rest) {
                for (RestServerResourceSpec resource : rest.getResources().values()) {
                    for (RestServerOperationSpec op : resource.getOperations().values()) {
                        rejectIfFlow(op.getCall(), "REST operation '" + op.getName() + "'",
                                flowsByNamespace);
                    }
                }
            }
        }
    }

    private static void rejectIfFlow(ServerCallSpec call, String label,
            Map<String, Set<String>> flowsByNamespace) {
        QualifiedName target = call != null ? QualifiedName.parse(call.getOperation()) : null;
        if (target != null && flowsByNamespace.containsKey(target.namespace())) {
            throw new IllegalArgumentException(label + " calls aggregate flow '" + target
                    + "' with 'call:'. Use 'ref: " + target + "' to expose an aggregate flow.");
        }
    }
}
