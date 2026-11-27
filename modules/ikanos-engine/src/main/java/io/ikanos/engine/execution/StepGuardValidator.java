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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Checks the {@code onlyIf:} guards of every step when a capability loads and in
 * {@code ikanos validate}.
 *
 * <ul>
 * <li>A guard that is not a bare name and does not parse as CEL is an <b>error</b>: the step
 * could never run.</li>
 * <li>A guard that names a variable the step cannot see is a <b>warning</b>: the variable is
 * absent at run time, so the step is always skipped, which is usually a typo. A step sees the
 * inputs and {@code with} values of its unit, the outputs of the steps before it, and the
 * caller root ({@code auth}).</li>
 * </ul>
 */
public final class StepGuardValidator {

    private static final Pattern URI_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)\\}");

    private StepGuardValidator() {
    }

    /** One step list and what its steps can read before the first step runs. */
    private record Container(String where, Map<String, OperationStepSpec> steps,
            Set<String> names) {
    }

    /**
     * @throws IllegalArgumentException naming each guard that does not parse
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    /** The guards that do not parse, each with where it is. */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        for (Container container : containers(spec)) {
            for (OperationStepSpec step : container.steps().values()) {
                String error = StepGuard.parseError(step.getOnlyIf());
                if (error != null) {
                    errors.add("The onlyIf of step '" + step.getName() + "' in "
                            + container.where() + " is not a valid CEL expression: " + error);
                }
            }
        }
        return errors;
    }

    /** The guards that name a variable their step cannot see. */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (Container container : containers(spec)) {
            Set<String> visible = new HashSet<>(container.names());
            for (OperationStepSpec step : container.steps().values()) {
                if (step.getOnlyIf() != null && StepGuard.parseError(step.getOnlyIf()) == null) {
                    for (String name : StepGuard.referencedNames(step.getOnlyIf())) {
                        if (!visible.contains(name)) {
                            warnings.add("The onlyIf of step '" + step.getName() + "' in "
                                    + container.where() + " reads '" + name + "', which no input,"
                                    + " 'with' value or earlier step provides: the step will"
                                    + " always be skipped.");
                        }
                    }
                }
                if (step.getName() != null) {
                    visible.add(step.getName());
                    visible.add(step.getName().replace('-', '_'));
                }
            }
        }
        return warnings;
    }

    private static List<Container> containers(IkanosSpec spec) {
        List<Container> containers = new ArrayList<>();
        if (spec == null || spec.getCapability() == null) {
            return containers;
        }
        List<ServerSpec> exposes = spec.getCapability().getExposes();
        if (exposes != null) {
            for (ServerSpec server : exposes) {
                if (server instanceof McpServerSpec mcp) {
                    addMcp(mcp, containers);
                } else if (server instanceof RestServerSpec rest) {
                    addRest(rest, containers);
                }
            }
        }
        Map<String, AggregateSpec> aggregates = spec.getCapability().getAggregates();
        if (aggregates != null) {
            for (AggregateSpec aggregate : aggregates.values()) {
                if (aggregate.getFlows() == null) {
                    continue;
                }
                for (Map.Entry<String, AggregateFlowSpec> flow : aggregate.getFlows().entrySet()) {
                    Set<String> names = names(flow.getValue().getInputParameters() == null ? null
                            : flow.getValue().getInputParameters().values(),
                            flow.getValue().getWith());
                    add(containers, "flow '" + aggregate.getNamespace() + "." + flow.getKey()
                            + "'", flow.getValue().getSteps(), names);
                }
            }
        }
        return containers;
    }

    private static void addMcp(McpServerSpec mcp, List<Container> containers) {
        if (mcp.getTools() != null) {
            for (Map.Entry<String, McpServerToolSpec> tool : mcp.getTools().entrySet()) {
                add(containers, "MCP tool '" + tool.getKey() + "'", tool.getValue().getSteps(),
                        names(tool.getValue().getInputParameters(), tool.getValue().getWith()));
            }
        }
        if (mcp.getResources() != null) {
            for (Map.Entry<String, McpServerResourceSpec> resource
                    : mcp.getResources().entrySet()) {
                Set<String> names = names(null, resource.getValue().getWith());
                String uri = resource.getValue().getUri();
                if (uri != null) {
                    Matcher variable = URI_VARIABLE.matcher(uri);
                    while (variable.find()) {
                        names.add(variable.group(1));
                    }
                }
                add(containers, "MCP resource '" + resource.getKey() + "'",
                        resource.getValue().getSteps(), names);
            }
        }
    }

    private static void addRest(RestServerSpec rest, List<Container> containers) {
        if (rest.getResources() == null) {
            return;
        }
        for (RestServerResourceSpec resource : rest.getResources().values()) {
            if (resource.getOperations() == null) {
                continue;
            }
            for (RestServerOperationSpec operation : resource.getOperations().values()) {
                Set<String> names = names(operation.getInputParameters(), operation.getWith());
                names.addAll(names(resource.getInputParameters(), null));
                add(containers, "REST operation '" + operation.getMethod() + " "
                        + resource.getPath() + "'", operation.getSteps(), names);
            }
        }
    }

    private static void add(List<Container> containers, String where,
            Map<String, OperationStepSpec> steps, Set<String> names) {
        if (steps != null && !steps.isEmpty()) {
            containers.add(new Container(where, steps, names));
        }
    }

    /** Input and {@code with} names, with the alias of each dashed name, and the caller root. */
    private static Set<String> names(Collection<InputParameterSpec> inputs,
            Map<String, Object> with) {
        Set<String> names = new HashSet<>();
        names.add(Resolver.CALLER_ROOT);
        if (inputs != null) {
            for (InputParameterSpec input : inputs) {
                if (input.getName() != null) {
                    names.add(input.getName());
                }
            }
        }
        if (with != null) {
            names.addAll(with.keySet());
        }
        for (String name : List.copyOf(names)) {
            names.add(name.replace('-', '_'));
        }
        return names;
    }
}
