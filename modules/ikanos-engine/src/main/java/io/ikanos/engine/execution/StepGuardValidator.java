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
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepEndSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Checks the {@code onlyIf:} guards of every step when a capability loads and in
 * {@code ikanos validate}.
 *
 * <ul>
 * <li>A guard that is not a bare name and does not parse as CEL is an <b>error</b>: the step
 * could never run.</li>
 * <li>A guard that compares an outcome ({@code outcome.<step>}) with a word that is not one of
 * {@link StepOutcomes#VOCABULARY} is an <b>error</b>: it can never hold.</li>
 * <li>A guard that names a variable the step cannot see is a <b>warning</b>: the variable is
 * absent at run time, so the step is always skipped, which is usually a typo. A step sees the
 * inputs and {@code with} values of its unit, the outputs of the steps before it, and the
 * caller root ({@code auth}).</li>
 * <li>A CEL guard that subtracts names which, joined by {@code -}, name something the step can
 * see ({@code get-ship.count > 0} when a step is named {@code get-ship}) is a <b>warning</b>: CEL
 * reads the dash as minus, and the name is written {@code get_ship}.</li>
 * </ul>
 */
public final class StepGuardValidator {

    private static final Pattern URI_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)\\}");

    private StepGuardValidator() {
    }

    /** One step list and what its steps can read before the first step runs. */
    private record Container(String where, Map<String, OperationStepSpec> steps,
            Set<String> names, boolean declaresOutcome) {
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

    /** A guard of a step: its {@code onlyIf}, or one of its success criteria. */
    private record Guard(String label, String expression) {
    }

    private static List<Guard> guardsOf(OperationStepSpec step, String where) {
        List<Guard> guards = new ArrayList<>();
        if (step.getOnlyIf() != null) {
            guards.add(new Guard("The onlyIf of step '" + step.getName() + "' in " + where,
                    step.getOnlyIf()));
        }
        if (step instanceof OperationStepCallSpec call && call.getSuccessCriteria() != null) {
            for (String criterion : call.getSuccessCriteria()) {
                guards.add(new Guard("A success criterion of step '" + step.getName() + "' in "
                        + where, criterion));
            }
        }
        return guards;
    }

    /**
     * The guards that do not parse, and those that compare an outcome with a word that is not
     * one, each with where it is.
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        for (Container container : containers(spec)) {
            if (container.declaresOutcome()) {
                errors.add(reserved("An input or a 'with' key", container.where()));
            }
            for (OperationStepSpec step : container.steps().values()) {
                if (StepOutcomes.ROOT.equals(step.getName())) {
                    errors.add(reserved("A step", container.where()));
                }
                for (Guard guard : guardsOf(step, container.where())) {
                    String error = StepGuard.parseError(guard.expression());
                    if (error != null) {
                        errors.add(guard.label() + " is not a valid CEL expression: " + error);
                        continue;
                    }
                    for (String unknown : StepGuard.unknownOutcomes(guard.expression())) {
                        errors.add(guard.label() + " compares an outcome with '" + unknown
                                + "', which is not one: use "
                                + String.join(", ", StepOutcomes.VOCABULARY) + ".");
                    }
                }
            }
        }
        return errors;
    }

    /**
     * The guards that name a variable their step cannot see, and the steps an unguarded
     * {@code end} makes unreachable.
     */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (Container container : containers(spec)) {
            Set<String> visible = new HashSet<>(container.names());
            // The steps before the current one, whose outcomes it can read
            Set<String> ran = new HashSet<>();
            String endedBy = null;
            for (OperationStepSpec step : container.steps().values()) {
                if (endedBy != null) {
                    warnings.add("Step '" + step.getName() + "' in " + container.where()
                            + " never runs: step '" + endedBy + "' before it always ends the"
                            + " flow. Guard that step with onlyIf.");
                    break;
                }
                // A step's success criteria see its own outcome and output.
                Set<String> own = new HashSet<>(visible);
                Set<String> ranOwn = new HashSet<>(ran);
                if (step.getName() != null) {
                    own.add(step.getName());
                    own.add(step.getName().replace('-', '_'));
                    ranOwn.add(step.getName());
                    ranOwn.add(step.getName().replace('-', '_'));
                }
                for (Guard guard : guardsOf(step, container.where())) {
                    if (StepGuard.parseError(guard.expression()) != null) {
                        continue;
                    }
                    boolean criterion = !guard.label().startsWith("The onlyIf");
                    for (String outcomeOf : StepGuard.outcomeSteps(guard.expression())) {
                        if (!(criterion ? ranOwn : ran).contains(outcomeOf)) {
                            warnings.add(guard.label() + " reads the outcome of '" + outcomeOf
                                    + "', which no earlier step has: "
                                    + (criterion ? "it never holds." : "the step will always"
                                            + " be skipped."));
                        }
                    }
                    Set<String> explained = new HashSet<>();
                    for (List<String> parts : StepGuard.subtractedNames(guard.expression())) {
                        String dashed = String.join("-", parts);
                        if ((criterion ? own : visible).contains(dashed)) {
                            warnings.add(guard.label() + " reads '" + String.join(" - ", parts)
                                    + "', a subtraction: CEL reads '-' as minus. To read '"
                                    + dashed + "', write '" + String.join("_", parts) + "'.");
                            explained.addAll(parts);
                        }
                    }
                    for (String name : StepGuard.referencedNames(guard.expression())) {
                        if (!(criterion ? own : visible).contains(name)
                                && !explained.contains(name)) {
                            warnings.add(guard.label() + " reads '" + name + "', which no input,"
                                    + " 'with' value or earlier step provides: "
                                    + (criterion ? "it never holds." : "the step will always"
                                            + " be skipped."));
                        }
                    }
                }
                if (step instanceof OperationStepEndSpec && step.getOnlyIf() == null) {
                    endedBy = step.getName();
                }
                if (step.getName() != null) {
                    visible.add(step.getName());
                    visible.add(step.getName().replace('-', '_'));
                    ran.add(step.getName());
                    ran.add(step.getName().replace('-', '_'));
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
                            + "'", flow.getValue().getSteps(), names, declaresOutcome(
                                    flow.getValue().getInputParameters() == null ? null
                                            : flow.getValue().getInputParameters().values(),
                                    flow.getValue().getWith()));
                }
            }
        }
        return containers;
    }

    private static void addMcp(McpServerSpec mcp, List<Container> containers) {
        if (mcp.getTools() != null) {
            for (Map.Entry<String, McpServerToolSpec> tool : mcp.getTools().entrySet()) {
                add(containers, "MCP tool '" + tool.getKey() + "'", tool.getValue().getSteps(),
                        names(tool.getValue().getInputParameters(), tool.getValue().getWith()),
                        declaresOutcome(tool.getValue().getInputParameters(),
                                tool.getValue().getWith()));
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
                        resource.getValue().getSteps(), names,
                        declaresOutcome(null, resource.getValue().getWith()));
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
                        + resource.getPath() + "'", operation.getSteps(), names,
                        declaresOutcome(operation.getInputParameters(), operation.getWith())
                                || declaresOutcome(resource.getInputParameters(), null));
            }
        }
    }

    private static void add(List<Container> containers, String where,
            Map<String, OperationStepSpec> steps, Set<String> names, boolean declaresOutcome) {
        if (steps != null && !steps.isEmpty()) {
            containers.add(new Container(where, steps, names, declaresOutcome));
        }
    }

    /**
     * The error for the reserved name {@code outcome}: a value under it would hide the step
     * outcomes, and could make a guard read the caller's data as an outcome.
     */
    private static String reserved(String what, String where) {
        return what + " in " + where + " is named '" + StepOutcomes.ROOT + "', which is reserved"
                + " for the outcomes of the steps (" + StepOutcomes.ROOT + ".<step>). Rename it.";
    }

    /** Whether the inputs or the {@code with} keys use the reserved name {@code outcome}. */
    private static boolean declaresOutcome(Collection<InputParameterSpec> inputs,
            Map<String, Object> with) {
        if (with != null && with.containsKey(StepOutcomes.ROOT)) {
            return true;
        }
        if (inputs != null) {
            for (InputParameterSpec input : inputs) {
                if (StepOutcomes.ROOT.equals(input.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Input and {@code with} names, with the alias of each dashed name, and the caller root. */
    private static Set<String> names(Collection<InputParameterSpec> inputs,
            Map<String, Object> with) {
        Set<String> names = new HashSet<>();
        names.add(Resolver.CALLER_ROOT);
        names.add(StepOutcomes.ROOT);
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
