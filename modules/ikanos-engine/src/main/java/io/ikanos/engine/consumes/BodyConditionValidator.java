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
package io.ikanos.engine.consumes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.ikanos.engine.util.JsonBodyMarkers;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Finds the {@code $onlyIf} markers of a consumed body that name no input of a caller (#1132).
 * Such an object is never sent from that caller, which is most likely a misspelled name.
 *
 * <p>Only the callers whose inputs are known from the spec are checked: an MCP tool, a REST
 * operation or an aggregate flow that calls the operation with {@code call} and no {@code steps}
 * or {@code ref}. Their inputs are their declared input parameters, the keys of their
 * {@code with} mappings, and the input parameters of the consumed adapter, resource and
 * operation. A dotted name, such as a step output, a binding or a caller claim, is not checked.
 * The findings are warnings: a name can still come from a source this check does not see.</p>
 */
public final class BodyConditionValidator {

    private BodyConditionValidator() {
    }

    /**
     * @param spec the capability spec, with its definitions expanded; may be null
     * @return one message per caller and {@code $onlyIf} name it does not provide
     */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        if (spec == null) {
            return warnings;
        }
        Map<String, ConsumedBody> bodies = consumedBodies(spec);
        if (bodies.isEmpty()) {
            return warnings;
        }
        for (ServerSpec server : exposes(spec)) {
            if (server instanceof McpServerSpec mcp && mcp.getTools() != null) {
                for (Map.Entry<String, McpServerToolSpec> entry : mcp.getTools().entrySet()) {
                    McpServerToolSpec tool = entry.getValue();
                    if (tool.getRef() != null || hasSteps(tool.getSteps())) {
                        continue;
                    }
                    Set<String> inputs = names(tool.getInputParameters());
                    inputs.addAll(keys(tool.getWith()));
                    check(tool.getCall(), inputs, "MCP tool '" + entry.getKey() + "' in '"
                            + mcp.getNamespace() + "'", bodies, warnings);
                }
            } else if (server instanceof RestServerSpec rest && rest.getResources() != null) {
                for (RestServerResourceSpec resource : rest.getResources().values()) {
                    if (resource.getOperations() == null) {
                        continue;
                    }
                    for (RestServerOperationSpec operation : resource.getOperations().values()) {
                        if (operation.getRef() != null || hasSteps(operation.getSteps())) {
                            continue;
                        }
                        // The adapter's own inputs reach the call too
                        Set<String> inputs = names(rest.getInputParameters());
                        inputs.addAll(names(resource.getInputParameters()));
                        inputs.addAll(names(operation.getInputParameters()));
                        inputs.addAll(keys(operation.getWith()));
                        check(operation.getCall(), inputs, "REST operation '"
                                + operation.getMethod() + " " + resource.getPath() + "' in '"
                                + rest.getNamespace() + "'", bodies, warnings);
                    }
                }
            }
        }
        for (AggregateSpec aggregate : aggregates(spec)) {
            if (aggregate.getFlows() == null) {
                continue;
            }
            for (Map.Entry<String, AggregateFlowSpec> entry : aggregate.getFlows().entrySet()) {
                AggregateFlowSpec flow = entry.getValue();
                if (hasSteps(flow.getSteps())) {
                    continue;
                }
                Set<String> inputs = flow.getInputParameters() == null ? new HashSet<>()
                        : new HashSet<>(flow.getInputParameters().keySet());
                inputs.addAll(keys(flow.getWith()));
                check(flow.getCall(), inputs, "aggregate '" + aggregate.getNamespace()
                        + "' flow '" + entry.getKey() + "'", bodies, warnings);
            }
        }
        return warnings;
    }

    /** The {@code $onlyIf} names of a consumed body, and the inputs its operation declares. */
    private record ConsumedBody(Set<String> conditions, Set<String> inputs) {
    }

    private static void check(ServerCallSpec call, Set<String> callerInputs, String caller,
            Map<String, ConsumedBody> bodies, List<String> warnings) {
        if (call == null || call.getOperation() == null) {
            return;
        }
        ConsumedBody body = bodies.get(call.getOperation());
        if (body == null) {
            return;
        }
        Set<String> inputs = new HashSet<>(callerInputs);
        inputs.addAll(keys(call.getWith()));
        inputs.addAll(body.inputs());
        for (String name : body.conditions()) {
            if (!name.contains(".") && !inputs.contains(name)) {
                warnings.add(caller + " calls " + call.getOperation() + ", whose body has '"
                        + JsonBodyMarkers.ONLY_IF + ": " + name + "', but '" + name
                        + "' is not one of its inputs: that object is never sent from it");
            }
        }
    }

    /** The consumed HTTP operations whose body has an {@code $onlyIf}, by {@code ns.operation}. */
    private static Map<String, ConsumedBody> consumedBodies(IkanosSpec spec) {
        Map<String, ConsumedBody> bodies = new LinkedHashMap<>();
        for (ClientSpec client : consumes(spec)) {
            if (!(client instanceof HttpClientSpec http) || http.getResources() == null) {
                continue;
            }
            for (HttpClientResourceSpec resource : http.getResources().values()) {
                if (resource.getOperations() == null) {
                    continue;
                }
                for (HttpClientOperationSpec operation : resource.getOperations().values()) {
                    if (!(operation.getBody() instanceof Map<?, ?> body)) {
                        continue;
                    }
                    Set<String> conditions = JsonBodyMarkers.conditions(body.get("data"));
                    if (conditions.isEmpty()) {
                        continue;
                    }
                    Set<String> inputs = names(http.getInputParameters());
                    inputs.addAll(names(resource.getInputParameters()));
                    inputs.addAll(names(operation.getInputParameters()));
                    bodies.put(http.getNamespace() + "." + operation.getName(),
                            new ConsumedBody(conditions, inputs));
                }
            }
        }
        return bodies;
    }

    private static boolean hasSteps(Map<?, ?> steps) {
        return steps != null && !steps.isEmpty();
    }

    private static Set<String> names(Collection<InputParameterSpec> parameters) {
        Set<String> names = new HashSet<>();
        if (parameters != null) {
            for (InputParameterSpec parameter : parameters) {
                if (parameter.getName() != null) {
                    names.add(parameter.getName());
                }
            }
        }
        return names;
    }

    private static Set<String> keys(Map<String, Object> with) {
        return with == null ? new HashSet<>() : new HashSet<>(with.keySet());
    }

    private static List<ClientSpec> consumes(IkanosSpec spec) {
        List<ClientSpec> clients = new ArrayList<>();
        if (spec.getConsumes() != null) {
            clients.addAll(spec.getConsumes());
        }
        CapabilitySpec capability = spec.getCapability();
        if (capability != null && capability.getConsumes() != null) {
            clients.addAll(capability.getConsumes());
        }
        return clients;
    }

    private static List<ServerSpec> exposes(IkanosSpec spec) {
        List<ServerSpec> servers = new ArrayList<>();
        if (spec.getExposes() != null) {
            servers.addAll(spec.getExposes());
        }
        CapabilitySpec capability = spec.getCapability();
        if (capability != null && capability.getExposes() != null) {
            servers.addAll(capability.getExposes());
        }
        return servers;
    }

    private static List<AggregateSpec> aggregates(IkanosSpec spec) {
        List<AggregateSpec> aggregates = new ArrayList<>();
        if (spec.getAggregates() != null) {
            aggregates.addAll(spec.getAggregates());
        }
        CapabilitySpec capability = spec.getCapability();
        if (capability != null && capability.getAggregates() != null) {
            aggregates.addAll(capability.getAggregates().values());
        }
        return aggregates;
    }
}
