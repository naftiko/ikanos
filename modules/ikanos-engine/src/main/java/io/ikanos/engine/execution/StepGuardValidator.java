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
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.OperationStepEndSpec;
import io.ikanos.spec.util.OperationStepForEachSpec;
import io.ikanos.spec.util.OperationStepParallelSpec;
import io.ikanos.spec.util.OperationStepSequenceSpec;
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

    /** The deepest nesting of tracks and sequences before a warning: memo decision, 3 levels. */
    static final int MAX_NESTING = 3;

    /** What a walk of the steps finds. */
    private record Findings(List<String> errors, List<String> warnings, Set<String> declared) {
    }

    /**
     * The guards that do not parse, those that compare an outcome with a word that is not one,
     * the reserved name {@code outcome}, and the misuses of tracks and sequences, each with where
     * it is.
     */
    public static List<String> findErrors(IkanosSpec spec) {
        return walk(spec).errors();
    }

    /**
     * The guards that name a variable their step cannot see, the steps an unguarded
     * {@code end} makes unreachable, and steps nested deeper than {@link #MAX_NESTING} levels.
     */
    public static List<String> findWarnings(IkanosSpec spec) {
        return walk(spec).warnings();
    }

    private static Findings walk(IkanosSpec spec) {
        Findings findings = new Findings(new ArrayList<>(), new ArrayList<>(), new HashSet<>());
        for (Container container : containers(spec)) {
            // The names no step produces: inputs, 'with' values, the roots, the items of loops
            findings.declared().clear();
            findings.declared().addAll(container.names());
            if (container.declaresOutcome()) {
                findings.errors().add(reserved("An input or a 'with' key", container.where()));
            }
            visit(container.steps(), new HashSet<>(container.names()), container.where(), 0,
                    Set.of(), false, findings);
        }
        return findings;
    }

    /**
     * Checks a step list.
     *
     * @param visible what its first step can read: inputs, {@code with} values, earlier steps
     * @param depth how many tracks and sequences hold this list
     * @param siblings in a track, the names of the other tracks of its group; empty otherwise
     * @param inTrack whether the list is, or is inside, a track of a parallel step
     */
    private static void visit(Map<String, OperationStepSpec> steps, Set<String> visible,
            String where, int depth, Set<String> siblings, boolean inTrack, Findings findings) {
        List<String> errors = findings.errors();
        List<String> warnings = findings.warnings();
        String endedBy = null;
        // The tracks of the parallel steps of this list, by name: each must be unique, as the
        // steps after the groups read every track under its name
        Map<String, String> trackOwners = new java.util.HashMap<>();
        for (OperationStepSpec step : List.copyOf(steps.values())) {
            if (endedBy != null) {
                warnings.add("Step '" + step.getName() + "' in " + where + " never runs: step '"
                        + endedBy + "' before it always ends the flow. Guard that step with"
                        + " onlyIf.");
                break;
            }
            if (StepOutcomes.ROOT.equals(step.getName())) {
                errors.add(reserved("A step", where));
            }
            // A track or a do that is a sequence is walked through its steps, never as a step
            // of a list: a sequence met here is misplaced, and would never run.
            if (step instanceof OperationStepSequenceSpec) {
                errors.add("Step '" + step.getName() + "' in " + where + " is a sequence, which"
                        + " is only allowed as a track of a parallel step or as the do of a"
                        + " forEach step.");
            }
            if (step instanceof OperationStepEndSpec && inTrack) {
                errors.add("Step '" + step.getName() + "' in " + where + " ends the flow from"
                        + " inside a parallel track, where it would end only its track. End the"
                        + " flow after the parallel step.");
            }
            if (step instanceof OperationStepElicitSpec && depth > 0) {
                errors.add("Step '" + step.getName() + "' in " + where + " asks the caller from"
                        + " inside a parallel track or a forEach step, where questions are not"
                        + " supported. Ask before that step.");
            }
            checkGuards(step, visible, where, siblings, findings);
            checkSiblingReads(step, where, siblings, findings);
            if (step instanceof OperationStepParallelSpec parallel) {
                for (String track : parallel.getTracks().keySet()) {
                    String owner = trackOwners.putIfAbsent(track, parallel.getName());
                    if (owner != null) {
                        errors.add("Track '" + track + "' of " + where + ", parallel step '"
                                + parallel.getName() + "' has the name of a track of parallel"
                                + " step '" + owner + "': the steps after them read each track"
                                + " under its name. Rename one.");
                    }
                }
                visitTracks(parallel, steps.keySet(), visible, where, depth, findings);
            }
            if (step instanceof OperationStepForEachSpec forEach) {
                visitForEach(forEach, visible, where, depth, siblings, inTrack, findings);
            }
            if (step instanceof OperationStepEndSpec && step.getOnlyIf() == null) {
                endedBy = step.getName();
            }
            addName(visible, step.getName());
        }
    }

    /**
     * Checks a {@code forEach} step: its {@code items} and {@code limit} are read like guards,
     * and its {@code do} sees the steps before it and the current item.
     */
    private static void visitForEach(OperationStepForEachSpec forEach, Set<String> visible,
            String where, int depth, Set<String> siblings, boolean inTrack,
            Findings findings) {
        String label = "The items of step '" + forEach.getName() + "' in " + where;
        String error = StepGuard.parseError(forEach.getItems());
        if (error != null) {
            findings.errors().add(label + " is not a valid CEL expression: " + error);
        } else {
            Set<String> explained = warnDashedNames(label, forEach.getItems(), visible,
                    findings.warnings());
            for (String name : StepGuard.referencedNames(forEach.getItems())) {
                if (explained.contains(name)) {
                    continue;
                }
                if (readsSibling(name, siblings)) {
                    findings.errors().add(label + " reads track '" + name + "', which runs at"
                            + " the same time: a track sees only the steps before its parallel"
                            + " step.");
                } else if (!visible.contains(name)) {
                    findings.warnings().add(label + " reads '" + name + "', which no input,"
                            + " 'with' value or earlier step provides: the step runs over no"
                            + " item.");
                }
            }
            // A step's output, at any level: an earlier step, a track, a step of an enclosing
            // list; not an input, a 'with' value or a loop's item.
            boolean overAStep = StepGuard.referencedNames(forEach.getItems()).stream()
                    .anyMatch(name -> visible.contains(name)
                            && !findings.declared().contains(name));
            if (overAStep && forEach.getLimit() == null) {
                findings.warnings().add("Step '" + forEach.getName() + "' in " + where
                        + " runs over the result of a step with no limit: a long list makes the"
                        + " call slow. Set 'limit', or bind it to an input.");
            }
        }
        if (forEach.getLimit() instanceof String limit && !limit.isBlank()) {
            String limitLabel = "The limit of step '" + forEach.getName() + "' in " + where;
            String limitError = StepGuard.parseError(limit);
            if (limitError != null) {
                findings.errors().add(limitLabel + " is not a valid CEL expression: " + limitError);
            }
            Set<String> explained = limitError != null ? Set.of()
                    : warnDashedNames(limitLabel, limit, visible, findings.warnings());
            for (String name : StepGuard.referencedNames(limit)) {
                if (explained.contains(name)) {
                    continue;
                }
                if (readsSibling(name, siblings)) {
                    findings.errors().add(limitLabel + " reads track '" + name + "', which runs"
                            + " at the same time: a track sees only the steps before its parallel"
                            + " step.");
                } else if (!visible.contains(name)) {
                    findings.warnings().add("The limit of step '" + forEach.getName() + "' in "
                            + where + " reads '" + name + "', which no input, 'with' value or"
                            + " earlier step provides: every item runs.");
                }
            }
        }
        OperationStepSpec body = forEach.getDo();
        if (body == null) {
            return;
        }
        String inner = where + ", forEach step '" + forEach.getName() + "'";
        // An iteration has no early exit: an end would end only the current item's run
        for (OperationStepSpec bodyStep : ParallelTracks.stepsOf(body.getName(), body).values()) {
            if (bodyStep instanceof OperationStepEndSpec) {
                findings.errors().add("Step '" + bodyStep.getName() + "' in " + inner + " ends"
                        + " the flow from inside a do, where it would end only the current item."
                        + " End the flow after the forEach step.");
            }
        }
        // One level per forEach step: a sequence do is the body, not a level
        int bodyDepth = depth + 1;
        if (bodyDepth > MAX_NESTING) {
            findings.warnings().add("The do of step '" + forEach.getName() + "' in " + where
                    + " is nested more than " + MAX_NESTING + " levels deep (parallel and"
                    + " forEach steps), which makes the flow hard to read and to trace.");
        }
        String item = forEach.itemName();
        if (StepOutcomes.ROOT.equals(item) || Resolver.CALLER_ROOT.equals(item)) {
            findings.errors().add("Step '" + forEach.getName() + "' in " + where + " names its"
                    + " item '" + item + "', which is reserved (" + StepOutcomes.ROOT
                    + ".<step> for the outcomes, " + Resolver.CALLER_ROOT + " for the caller's"
                    + " claims). Choose another 'as'.");
        }
        Set<String> bodyVisible = new HashSet<>(visible);
        addName(bodyVisible, item);
        addName(findings.declared(), item);
        if (body instanceof OperationStepSequenceSpec) {
            // The sequence's own guard, evaluated for each item before its steps run
            checkGuards(body, bodyVisible, inner, siblings, findings);
        }
        visit(ParallelTracks.stepsOf(body.getName(), body), bodyVisible, inner, bodyDepth,
                siblings, inTrack, findings);
    }

    private static void visitTracks(OperationStepParallelSpec parallel, Set<String> stepNames,
            Set<String> visible, String where, int depth, Findings findings) {
        Map<String, OperationStepSpec> tracks = parallel.getTracks();
        String inner = where + ", parallel step '" + parallel.getName() + "'";
        for (Map.Entry<String, OperationStepSpec> track : tracks.entrySet()) {
            String name = track.getKey();
            if (stepNames.contains(name)) {
                findings.errors().add("Track '" + name + "' of " + inner + " has the name of a"
                        + " step of the same list: the steps after it read the track's output"
                        + " under its name. Rename one.");
            }
            Set<String> others = new HashSet<>(tracks.keySet());
            others.remove(name);
            Set<String> trackVisible = new HashSet<>(visible);
            // One level per parallel step: a sequence track is the track's body, not a level
            int trackDepth = depth + 1;
            if (trackDepth > MAX_NESTING) {
                findings.warnings().add("Track '" + name + "' of " + inner + " is nested more"
                        + " than " + MAX_NESTING + " levels deep (parallel and forEach steps),"
                        + " which makes the flow hard to read and to trace.");
            }
            if (track.getValue() instanceof OperationStepSequenceSpec sequence) {
                // The sequence's own guard, evaluated before its steps run
                checkGuards(sequence, trackVisible, inner, others, findings);
                visit(sequence.getSteps(), trackVisible, inner + ", track '" + name + "'",
                        trackDepth, others, true, findings);
            } else {
                Map<String, OperationStepSpec> one = new java.util.LinkedHashMap<>();
                one.put(name, track.getValue());
                visit(one, trackVisible, inner, trackDepth, others, true, findings);
            }
        }
        // The steps after the group read each track under its name.
        tracks.keySet().forEach(name -> addName(visible, name));
    }

    private static void checkGuards(OperationStepSpec step, Set<String> visible, String where,
            Set<String> siblings, Findings findings) {
        // A step's success criteria see its own outcome and output.
        Set<String> own = new HashSet<>(visible);
        addName(own, step.getName());
        for (Guard guard : guardsOf(step, where)) {
            String error = StepGuard.parseError(guard.expression());
            if (error != null) {
                findings.errors().add(guard.label() + " is not a valid CEL expression: " + error);
                continue;
            }
            for (String unknown : StepGuard.unknownOutcomes(guard.expression())) {
                findings.errors().add(guard.label() + " compares an outcome with '" + unknown
                        + "', which is not one: use "
                        + String.join(", ", StepOutcomes.VOCABULARY) + ".");
            }
            boolean criterion = !guard.label().startsWith("The onlyIf");
            for (String outcomeOf : StepGuard.outcomeSteps(guard.expression())) {
                if (readsSibling(outcomeOf, siblings)) {
                    findings.errors().add(guard.label() + " reads the outcome of track '"
                            + outcomeOf + "', which runs at the same time: a track sees only the"
                            + " steps before its parallel step.");
                } else if (!(criterion ? own : visible).contains(outcomeOf)) {
                    findings.warnings().add(guard.label() + " reads the outcome of '" + outcomeOf
                            + "', which no earlier step has: "
                            + (criterion ? "it never holds." : "the step will always be"
                                    + " skipped."));
                }
            }
            Set<String> explained = warnDashedNames(guard.label(), guard.expression(),
                    criterion ? own : visible, findings.warnings());
            for (String name : StepGuard.referencedNames(guard.expression())) {
                if (readsSibling(name, siblings)) {
                    findings.errors().add(guard.label() + " reads track '" + name
                            + "', which runs at the same time: a track sees only the steps"
                            + " before its parallel step.");
                } else if (!(criterion ? own : visible).contains(name)
                        && !explained.contains(name)) {
                    findings.warnings().add(guard.label() + " reads '" + name + "', which no"
                            + " input, 'with' value or earlier step provides: "
                            + (criterion ? "it never holds." : "the step will always be"
                                    + " skipped."));
                }
            }
        }
    }

    /**
     * Refuses a step of a track that reads another track of its group: in its {@code with}
     * (a call's or a script's, nested values included), or a lookup's {@code index} or
     * {@code lookupValue}, by template ({@code {{profile.id}}}), by qualified name
     * ({@code orders.profile}) or by JSONPath ({@code $.profile.id}).
     */
    private static void checkSiblingReads(OperationStepSpec step, String where,
            Set<String> siblings, Findings findings) {
        if (siblings.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        if (step instanceof OperationStepCallSpec call) {
            collectStrings(call.getWith(), values);
        } else if (step instanceof io.ikanos.spec.scripting.OperationStepScriptSpec script) {
            collectStrings(script.getWith(), values);
        } else if (step instanceof io.ikanos.spec.util.OperationStepLookupSpec lookup) {
            collectStrings(lookup.getIndex(), values);
            collectStrings(lookup.getLookupValue(), values);
        }
        for (String sibling : siblings) {
            if (values.stream().anyMatch(value -> readsValue(value, sibling))) {
                findings.errors().add("Step '" + step.getName() + "' in " + where
                        + " reads track '" + sibling + "', which runs at the same time: a track"
                        + " sees only the steps before its parallel step.");
            }
        }
    }

    private static void collectStrings(Object value, List<String> strings) {
        if (value instanceof String text) {
            strings.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectStrings(item, strings));
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> collectStrings(item, strings));
        }
    }

    /**
     * Whether a value reads {@code name}: as a template, as a qualified name
     * ({@code namespace.name}, {@code namespace.name.field}), as a JSONPath ({@code $.name}) or
     * as the whole value (a lookup's {@code index}).
     */
    static boolean readsValue(String value, String name) {
        String quoted = Pattern.quote(name);
        return readsName(value, name) || value.strip().equals(name)
                || Pattern.compile("^\\s*[A-Za-z0-9_-]+\\." + quoted + "(\\.|\\[|\\s*$)")
                        .matcher(value).find()
                || Pattern.compile("^\\s*\\$\\." + quoted + "(\\.|\\[|\\s*$)").matcher(value)
                        .find();
    }

    /**
     * Warns about each subtraction of names in a CEL expression that, joined by {@code -}, name
     * something visible ({@code get-ship.count} when a step is named {@code get-ship}): CEL reads
     * the dash as minus, and the name is written {@code get_ship}.
     *
     * @return the names those subtractions explain, not to be reported again as unknown
     */
    static Set<String> warnDashedNames(String label, String expression, Set<String> visible,
            List<String> warnings) {
        Set<String> explained = new HashSet<>();
        for (List<String> parts : StepGuard.subtractedNames(expression)) {
            String dashed = String.join("-", parts);
            if (visible.contains(dashed)) {
                warnings.add(label + " reads '" + String.join(" - ", parts) + "', a subtraction:"
                        + " CEL reads '-' as minus. To read '" + dashed + "', write '"
                        + String.join("_", parts) + "'.");
                explained.addAll(parts);
            }
        }
        return explained;
    }

    private static boolean readsSibling(String name, Set<String> siblings) {
        for (String sibling : siblings) {
            if (sibling.equals(name) || sibling.replace('-', '_').equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@code with} value reads {@code name} through a template ({@code {{name}}},
     * {@code {{name.field}}}, a section or an unescaped tag). A plain literal such as
     * {@code billing.eu} reads nothing: only templates read the run's values.
     */
    static boolean readsName(String value, String name) {
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile(
                "\\{\\{[#^&{]?\\s*" + java.util.regex.Pattern.quote(name) + "(\\.|\\s*\\})");
        return reference.matcher(value).find();
    }

    private static void addName(Set<String> names, String name) {
        if (name != null) {
            names.add(name);
            names.add(name.replace('-', '_'));
        }
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
