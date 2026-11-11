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
package io.ikanos.engine.definitions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.restlet.Context;

import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.DefinitionsSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;

/**
 * Expands the shared {@code definitions} of MCP adapters and aggregates into the tools and flows
 * that use them (#910).
 *
 * <p>An MCP adapter or an aggregate declares named {@code inputParameterSets} and
 * {@code schemas} in its {@code definitions} block. They are visible to that namespace only:</p>
 * <ul>
 *   <li>{@code inputParameterSets: [names]} on an MCP tool or a flow applies the sets in order,
 *       then the unit's own {@code inputParameters}. An own entry replaces the set's entry with
 *       the same name whole, at the set entry's position. Two sets of one unit must not declare
 *       the same parameter.</li>
 *   <li>{@code schema: name} on an input (top level, {@code items}, {@code properties}), inside
 *       another schema, and in orchestrated (steps-mode) outputs, is replaced by a copy of the
 *       schema. Beside {@code schema:} an input only takes {@code description} (which replaces
 *       the schema's) and, at the top level, {@code required}; an output takes no other key.</li>
 *   <li>In an output, a schema gives only what an orchestrated output can declare: its
 *       {@code type} ({@code integer} becomes {@code number}), its {@code properties}, and its
 *       {@code items} when they are objects with properties. Input-only keywords
 *       ({@code description}, {@code enum}, {@code format}, {@code minimum}, {@code maximum},
 *       {@code required}, {@code additionalProperties}) do not apply to an output.</li>
 * </ul>
 *
 * <p>The result is the inline equivalent: once expanded, a unit holds no reference, so
 * {@code tools/list} advertises the same {@code inputSchema} and {@code outputSchema} as if the
 * definitions had been written out. Expansion runs after imports (an imported adapter or
 * aggregate carries its definitions) and before {@code ref} resolution, so a {@code ref} tool
 * whose sets give it inputs advertises them instead of the flow's, as its own
 * {@code inputParameters} would.</p>
 *
 * <p>Errors: an unknown reference, a reference of the wrong kind, a qualified ({@code a.b}) or
 * {@code $ref} reference, the same parameter from two sets of one unit, a cycle between schemas,
 * keys beside {@code schema:}, and {@code schema:} in an output that is not orchestrated.
 * Warning: a definition that no unit uses. Expansion is bounded by
 * {@link #MAX_EXPANDED_NODES} per namespace, so a document cannot multiply itself without
 * limit.</p>
 */
public final class DefinitionsExpander {

    /**
     * Upper bound on the parameter declarations that the definitions of one namespace may expand
     * to. Schemas that reference each other without a cycle can still grow exponentially (two
     * properties of the same schema at every level), so expansion stops past this bound.
     */
    public static final int MAX_EXPANDED_NODES = 100_000;

    /**
     * Findings of an expansion.
     *
     * @param errors   problems that make the document unusable, in document order
     * @param warnings problems that do not prevent loading, in document order
     */
    public record Result(List<String> errors, List<String> warnings) {
    }

    private DefinitionsExpander() {
    }

    /**
     * Expand the definitions of a capability being loaded. Warnings are logged; errors fail the
     * load.
     *
     * @param spec the capability, with its imports already resolved
     * @throws IllegalArgumentException when a definition or one of its uses is invalid
     */
    public static void expand(IkanosSpec spec) {
        Result result = expandAll(spec);
        for (String warning : result.warnings()) {
            Context.getCurrentLogger().log(Level.WARNING, warning);
        }
        if (!result.errors().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", result.errors()));
        }
    }

    /**
     * Expand the definitions of every MCP adapter and aggregate of a document, in the
     * capability and at the root of a standalone file, and report what is wrong.
     *
     * @param spec the document; its tools and flows are rewritten in place
     * @return the errors and warnings found
     */
    public static Result expandAll(IkanosSpec spec) {
        Set<String> errors = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        if (spec == null) {
            return new Result(List.of(), List.of());
        }
        CapabilitySpec capability = spec.getCapability();
        if (capability != null) {
            expandExposes(capability.getExposes(), errors, warnings);
            if (capability.getAggregates() != null) {
                expandAggregates(capability.getAggregates().values(), errors, warnings);
            }
        }
        expandExposes(spec.getExposes(), errors, warnings);
        expandAggregates(spec.getAggregates(), errors, warnings);
        return new Result(List.copyOf(errors), List.copyOf(warnings));
    }

    private static void expandExposes(List<ServerSpec> exposes, Set<String> errors,
            Set<String> warnings) {
        if (exposes == null) {
            return;
        }
        for (ServerSpec server : exposes) {
            if (server instanceof McpServerSpec mcp) {
                Namespace ns = new Namespace("MCP adapter '" + mcp.getNamespace() + "'", "tool",
                        mcp.getDefinitions(), errors, warnings);
                for (McpServerToolSpec tool : mcp.getTools().values()) {
                    ns.expandTool(tool);
                }
                ns.finish();
            }
        }
    }

    private static void expandAggregates(Iterable<AggregateSpec> aggregates, Set<String> errors,
            Set<String> warnings) {
        if (aggregates == null) {
            return;
        }
        for (AggregateSpec aggregate : aggregates) {
            if (aggregate == null || aggregate.getFlows() == null) {
                continue;
            }
            Namespace ns = new Namespace("aggregate '" + aggregate.getNamespace() + "'", "flow",
                    aggregate.getDefinitions(), errors, warnings);
            for (AggregateFlowSpec flow : aggregate.getFlows().values()) {
                ns.expandFlow(flow);
            }
            ns.finish();
        }
    }

    /**
     * The expansion state of one namespace (MCP adapter or aggregate): its definitions, which of
     * them are used, the schemas that form a cycle, and the expansion budget.
     */
    static final class Namespace {

        private final String owner;
        private final String unitKind;
        private final Map<String, Map<String, InputParameterSpec>> sets;
        private final Map<String, InputParameterSpec> schemas;
        private final Set<String> errors;
        private final Set<String> warnings;
        private final Set<String> usedSets = new HashSet<>();
        private final Set<String> usedSchemas = new HashSet<>();
        private final Set<String> cyclic = new HashSet<>();
        private int expandedNodes;
        private boolean overBudget;

        Namespace(String owner, String unitKind, DefinitionsSpec definitions, Set<String> errors,
                Set<String> warnings) {
            this.owner = owner;
            this.unitKind = unitKind;
            this.sets = definitions != null && definitions.getInputParameterSets() != null
                    ? definitions.getInputParameterSets() : Map.of();
            this.schemas = definitions != null && definitions.getSchemas() != null
                    ? definitions.getSchemas() : Map.of();
            this.errors = errors;
            this.warnings = warnings;
            findCycles();
        }

        void expandTool(McpServerToolSpec tool) {
            String location = owner + ", " + unitKind + " '" + tool.getName() + "'";
            Map<String, InputParameterSpec> own = new LinkedHashMap<>();
            for (InputParameterSpec param : tool.getInputParameters()) {
                own.put(param.getName(), param);
            }
            Map<String, InputParameterSpec> inputs =
                    expandInputs(location, tool.getInputParameterSets(), own);
            if (inputs != null) {
                tool.setInputParameters(inputs);
                tool.setInputParameterSets(List.of());
            }
            boolean orchestrated = tool.getSteps() != null && !tool.getSteps().isEmpty();
            tool.setOutputParameters(expandOutputs(location, orchestrated,
                    tool.getOutputParameters()));
        }

        void expandFlow(AggregateFlowSpec flow) {
            String location = owner + ", " + unitKind + " '" + flow.getName() + "'";
            Map<String, InputParameterSpec> own = flow.getInputParameters() != null
                    ? new LinkedHashMap<>(flow.getInputParameters()) : new LinkedHashMap<>();
            Map<String, InputParameterSpec> inputs =
                    expandInputs(location, flow.getInputParameterSets(), own);
            if (inputs != null) {
                flow.setInputParameters(inputs);
                flow.setInputParameterSets(List.of());
            }
            boolean orchestrated = flow.getSteps() != null && !flow.getSteps().isEmpty();
            flow.setOutputParameters(expandOutputs(location, orchestrated,
                    flow.getOutputParameters()));
        }

        /**
         * Report the definitions that no unit uses, after checking their content: a definition
         * that is never used is still checked, so a mistake in it does not wait for its first
         * use.
         */
        void finish() {
            Set<String> setsInUse = new HashSet<>(usedSets);
            Set<String> schemasInUse = new HashSet<>(usedSchemas);
            for (Map.Entry<String, Map<String, InputParameterSpec>> set : sets.entrySet()) {
                for (InputParameterSpec param : set.getValue().values()) {
                    expandInput(copy(param), setLocation(set.getKey(), param.getName()), true);
                }
            }
            for (String name : schemas.keySet()) {
                resolveSchema(name, owner + ", schema '" + name + "'");
            }
            for (String name : sets.keySet()) {
                if (!setsInUse.contains(name)) {
                    warnings.add(owner + " declares the input parameter set '" + name
                            + "', which no " + unitKind + " uses.");
                }
            }
            for (String name : schemas.keySet()) {
                if (!schemasInUse.contains(name)) {
                    warnings.add(owner + " declares the schema '" + name + "', which no "
                            + unitKind + " uses.");
                }
            }
        }

        /**
         * Merge the sets of a unit, in order, then its own inputs, and expand every schema
         * reference in them.
         *
         * @return the expanded inputs, or {@code null} when the unit uses no definition
         */
        Map<String, InputParameterSpec> expandInputs(String location, List<String> setNames,
                Map<String, InputParameterSpec> own) {
            boolean usesSets = setNames != null && !setNames.isEmpty();
            boolean usesSchemas = false;
            for (InputParameterSpec param : own.values()) {
                usesSchemas |= referencesSchema(param);
            }
            if (!usesSets && !usesSchemas) {
                return null;
            }
            Map<String, InputParameterSpec> merged = new LinkedHashMap<>();
            Map<String, String> origins = new LinkedHashMap<>();
            // A mistake inside a set entry is reported once, at the set, not at every unit.
            if (usesSets) {
                for (String setName : setNames) {
                    Map<String, InputParameterSpec> set = resolveSet(setName, location);
                    if (set == null) {
                        continue;
                    }
                    for (InputParameterSpec param : set.values()) {
                        String previous = origins.get(param.getName());
                        if (previous != null) {
                            errors.add(location + ": input '" + param.getName()
                                    + "' comes from both input parameter sets '" + previous
                                    + "' and '" + setName + "'. Keep it in one set, or declare"
                                    + " it on the " + unitKind + ".");
                            continue;
                        }
                        origins.put(param.getName(), setName);
                        merged.put(param.getName(), copy(param));
                    }
                }
            }
            merged.putAll(own);
            Map<String, InputParameterSpec> expanded = new LinkedHashMap<>();
            for (Map.Entry<String, InputParameterSpec> entry : merged.entrySet()) {
                String key = entry.getKey();
                String where = origins.containsKey(key) && !own.containsKey(key)
                        ? setLocation(origins.get(key), key)
                        : location + ", input '" + key + "'";
                expanded.put(key, expandInput(entry.getValue(), where, true));
            }
            return expanded;
        }

        /**
         * Expand the schema references of an input, in place, and return it, or the expanded
         * schema copy that replaces it.
         *
         * @param topLevel whether the input is a tool or flow input, which also takes
         *                 {@code required} beside {@code schema:}
         */
        InputParameterSpec expandInput(InputParameterSpec param, String location,
                boolean topLevel) {
            if (param == null || !withinBudget()) {
                return param;
            }
            if (param.getSchema() != null) {
                checkInputBesideSchema(param, location, topLevel);
                InputParameterSpec resolved = resolveSchema(param.getSchema(), location);
                if (resolved == null) {
                    return param;
                }
                resolved.setName(param.getName());
                resolved.setRequired(param.isRequired());
                if (param.getDescription() != null) {
                    resolved.setDescription(param.getDescription());
                }
                return resolved;
            }
            if (param.getItems() != null) {
                param.setItems(expandInput(param.getItems(), location + ", items", false));
            }
            if (param.getValues() != null) {
                param.setValues(expandInput(param.getValues(), location + ", values", false));
            }
            List<InputParameterSpec> properties = param.getProperties();
            for (int i = 0; i < properties.size(); i++) {
                InputParameterSpec property = properties.get(i);
                properties.set(i, expandInput(property,
                        location + ", property '" + property.getName() + "'", false));
            }
            return param;
        }

        /**
         * Expand the schema references of the outputs of a unit. Only orchestrated outputs take
         * {@code schema:}: mapped (call-mode) outputs keep a mapping on every leaf.
         */
        List<OutputParameterSpec> expandOutputs(String location, boolean orchestrated,
                List<OutputParameterSpec> outputs) {
            List<OutputParameterSpec> expanded = new ArrayList<>();
            if (outputs == null) {
                return expanded;
            }
            for (OutputParameterSpec output : outputs) {
                String where = location + ", output '" + output.getName() + "'";
                if (orchestrated) {
                    expanded.add(expandOutput(output, where));
                } else {
                    if (referencesSchema(output)) {
                        errors.add(where + ": 'schema:' is only allowed in orchestrated (steps)"
                                + " outputs. Mapped outputs keep a 'mapping' on every leaf.");
                    }
                    expanded.add(output);
                }
            }
            return expanded;
        }

        OutputParameterSpec expandOutput(OutputParameterSpec param, String location) {
            if (param == null || !withinBudget()) {
                return param;
            }
            if (param.getSchema() != null) {
                checkOutputBesideSchema(param, location);
                InputParameterSpec resolved = resolveSchema(param.getSchema(), location);
                if (resolved == null) {
                    return param;
                }
                OutputParameterSpec output = toOutput(resolved);
                output.setName(param.getName());
                return output;
            }
            if (param.getItems() != null) {
                param.setItems(expandOutput(param.getItems(), location + ", items"));
            }
            List<OutputParameterSpec> properties = param.getProperties();
            for (int i = 0; i < properties.size(); i++) {
                OutputParameterSpec property = properties.get(i);
                properties.set(i, expandOutput(property,
                        location + ", property '" + property.getName() + "'"));
            }
            return param;
        }

        /**
         * Resolve a set name of this namespace, reporting an unknown name, a schema name or a
         * qualified name.
         */
        Map<String, InputParameterSpec> resolveSet(String name, String location) {
            if (!isBareName(name, location)) {
                return null;
            }
            Map<String, InputParameterSpec> set = sets.get(name);
            if (set == null) {
                if (schemas.containsKey(name)) {
                    errors.add(location + ": '" + name + "' is a schema, not an input parameter"
                            + " set. Use it with 'schema: " + name + "' on an input.");
                } else {
                    errors.add(location + ": unknown input parameter set '" + name + "'. "
                            + owner + " declares " + names(sets.keySet()) + ".");
                }
                return null;
            }
            usedSets.add(name);
            return set;
        }

        /**
         * Resolve a schema name of this namespace into a fresh, fully expanded copy of the
         * schema.
         *
         * @return the copy, or {@code null} when the name cannot be resolved
         */
        InputParameterSpec resolveSchema(String name, String location) {
            if (!isBareName(name, location)) {
                return null;
            }
            InputParameterSpec schema = schemas.get(name);
            if (schema == null) {
                if (sets.containsKey(name)) {
                    errors.add(location + ": '" + name + "' is an input parameter set, not a"
                            + " schema. Use it with 'inputParameterSets: [" + name + "]' on the "
                            + unitKind + ".");
                } else {
                    errors.add(location + ": unknown schema '" + name + "'. " + owner
                            + " declares " + names(schemas.keySet()) + ".");
                }
                return null;
            }
            usedSchemas.add(name);
            if (cyclic.contains(name)) {
                return null;
            }
            return expandInput(copy(schema), owner + ", schema '" + name + "'", false);
        }

        private String setLocation(String set, String input) {
            return owner + ", input parameter set '" + set + "', input '" + input + "'";
        }

        private boolean isBareName(String name, String location) {
            if (name != null && name.matches("[a-zA-Z0-9-]+")) {
                return true;
            }
            errors.add(location + ": '" + name + "' is not the bare name of a definition."
                    + " Definitions are local to their MCP adapter or aggregate and referenced"
                    + " by name: qualified names ('a.b') and '$ref' pointers are not supported.");
            return false;
        }

        private boolean withinBudget() {
            expandedNodes++;
            if (expandedNodes <= MAX_EXPANDED_NODES) {
                return true;
            }
            if (!overBudget) {
                overBudget = true;
                errors.add(owner + ": its definitions expand to more than "
                        + MAX_EXPANDED_NODES + " parameter declarations. Expansion is bounded,"
                        + " so that a document cannot multiply itself without limit.");
            }
            return false;
        }

        private void checkInputBesideSchema(InputParameterSpec param, String location,
                boolean topLevel) {
            List<String> extra = new ArrayList<>();
            addIf(extra, "type", param.getType() != null);
            addIf(extra, "in", param.getIn() != null);
            addIf(extra, "template", param.getTemplate() != null);
            addIf(extra, "const", param.getConstant() != null);
            addIf(extra, "value", param.getValue() != null);
            addIf(extra, "wireName", param.getWireName() != null);
            addIf(extra, "style", param.getStyle() != null);
            addIf(extra, "explode", param.getExplode() != null);
            addIf(extra, "selector", param.getSelector() != null);
            addIf(extra, "items", param.getItems() != null);
            addIf(extra, "values", param.getValues() != null);
            addIf(extra, "properties", !param.getProperties().isEmpty());
            addIf(extra, topLevel ? "requiredProperties" : "required",
                    !param.getRequired().isEmpty());
            addIf(extra, "enum", !param.getEnumeration().isEmpty());
            addIf(extra, "format", param.getFormat() != null);
            addIf(extra, "minimum", param.getMinimum() != null);
            addIf(extra, "maximum", param.getMaximum() != null);
            addIf(extra, "additionalProperties", param.getAdditionalProperties() != null);
            addIf(extra, "maxLength", param.getMaxLength() != null);
            addIf(extra, "precision", param.getPrecision() != null);
            addIf(extra, "scale", param.getScale() != null);
            addIf(extra, "contentEncoding", param.getContentEncoding() != null);
            addIf(extra, "contentCompression", param.getContentCompression() != null);
            addIf(extra, "contentMediaType", param.getContentMediaType() != null);
            if (!extra.isEmpty()) {
                errors.add(location + ": 'schema: " + param.getSchema() + "' takes no other key"
                        + " than 'description'" + (topLevel ? " and 'required'" : "")
                        + ", found " + extra + ". Change the schema, or declare the input"
                        + " inline.");
            }
        }

        private void checkOutputBesideSchema(OutputParameterSpec param, String location) {
            List<String> extra = new ArrayList<>();
            addIf(extra, "type", param.getType() != null);
            addIf(extra, "description", param.getDescription() != null);
            addIf(extra, "mapping", param.getMapping() != null);
            addIf(extra, "value", param.getValue() != null);
            addIf(extra, "items", param.getItems() != null);
            addIf(extra, "values", param.getValues() != null);
            addIf(extra, "properties", !param.getProperties().isEmpty());
            if (!extra.isEmpty()) {
                errors.add(location + ": 'schema: " + param.getSchema() + "' takes no other key"
                        + " in an output, found " + extra + ".");
            }
        }

        private static void addIf(List<String> keys, String key, boolean present) {
            if (present) {
                keys.add(key);
            }
        }

        /**
         * Find the schemas that reference each other in a cycle, report each cycle once, and
         * remember its members so that expansion never follows it.
         */
        private void findCycles() {
            Map<String, Integer> state = new LinkedHashMap<>();
            for (String name : schemas.keySet()) {
                visit(name, state, new ArrayList<>());
            }
        }

        private void visit(String name, Map<String, Integer> state, List<String> path) {
            Integer current = state.get(name);
            if (current != null && current == 2) {
                return;
            }
            if (current != null && current == 1) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(name),
                        path.size()));
                cyclic.addAll(cycle);
                cycle.add(name);
                errors.add(owner + ": schemas reference each other in a cycle: "
                        + String.join(" -> ", cycle) + ".");
                return;
            }
            state.put(name, 1);
            path.add(name);
            Set<String> references = new LinkedHashSet<>();
            collectReferences(schemas.get(name), references);
            for (String reference : references) {
                if (schemas.containsKey(reference)) {
                    visit(reference, state, path);
                }
            }
            path.remove(path.size() - 1);
            state.put(name, 2);
        }

        private static void collectReferences(InputParameterSpec param, Set<String> references) {
            if (param == null) {
                return;
            }
            if (param.getSchema() != null) {
                references.add(param.getSchema());
            }
            collectReferences(param.getItems(), references);
            collectReferences(param.getValues(), references);
            for (InputParameterSpec property : param.getProperties()) {
                collectReferences(property, references);
            }
        }

        private static String names(Set<String> names) {
            return names.isEmpty() ? "none" : String.join(", ", names);
        }
    }

    /** Whether an input, or one of its nested declarations, uses {@code schema:}. */
    static boolean referencesSchema(InputParameterSpec param) {
        if (param == null) {
            return false;
        }
        if (param.getSchema() != null) {
            return true;
        }
        if (referencesSchema(param.getItems()) || referencesSchema(param.getValues())) {
            return true;
        }
        for (InputParameterSpec property : param.getProperties()) {
            if (referencesSchema(property)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an output, or one of its nested declarations, uses {@code schema:}. */
    static boolean referencesSchema(OutputParameterSpec param) {
        if (param == null) {
            return false;
        }
        if (param.getSchema() != null) {
            return true;
        }
        if (referencesSchema(param.getItems()) || referencesSchema(param.getValues())) {
            return true;
        }
        for (OutputParameterSpec property : param.getProperties()) {
            if (referencesSchema(property)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert an expanded schema into the orchestrated output it describes: its type
     * ({@code integer} becomes {@code number}, as orchestrated outputs have no integer), its
     * properties, and its items when they are objects with properties (the only items an
     * orchestrated output declares).
     */
    static OutputParameterSpec toOutput(InputParameterSpec schema) {
        OutputParameterSpec output = new OutputParameterSpec();
        output.setName(schema.getName());
        String type = schema.getType();
        output.setType("integer".equals(type) ? "number" : type);
        if ("object".equals(type)) {
            for (InputParameterSpec property : schema.getProperties()) {
                output.getProperties().add(toOutput(property));
            }
        } else if ("array".equals(type)) {
            InputParameterSpec items = schema.getItems();
            if (items != null && "object".equals(items.getType())
                    && !items.getProperties().isEmpty()) {
                OutputParameterSpec itemsOutput = toOutput(items);
                itemsOutput.setName(null);
                output.setItems(itemsOutput);
            }
        }
        return output;
    }

    /** Deep copy of an input declaration, so that every use of a definition owns its copy. */
    static InputParameterSpec copy(InputParameterSpec source) {
        if (source == null) {
            return null;
        }
        InputParameterSpec copy = new InputParameterSpec();
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setDescription(source.getDescription());
        copy.setSchema(source.getSchema());
        copy.setIn(source.getIn());
        copy.setTemplate(source.getTemplate());
        copy.setRequired(source.isRequired());
        copy.setValue(source.getValue());
        copy.setWireName(source.getWireName());
        copy.setStyle(source.getStyle());
        copy.setExplode(source.getExplode());
        copy.setFormat(source.getFormat());
        copy.setMinimum(source.getMinimum());
        copy.setMaximum(source.getMaximum());
        copy.setAdditionalProperties(source.getAdditionalProperties());
        copy.setConstant(source.getConstant());
        copy.setSelector(source.getSelector());
        copy.setMaxLength(source.getMaxLength());
        copy.setPrecision(source.getPrecision());
        copy.setScale(source.getScale());
        copy.setContentEncoding(source.getContentEncoding());
        copy.setContentCompression(source.getContentCompression());
        copy.setContentMediaType(source.getContentMediaType());
        copy.setItems(copy(source.getItems()));
        copy.setValues(copy(source.getValues()));
        for (InputParameterSpec property : source.getProperties()) {
            copy.getProperties().add(copy(property));
        }
        copy.getRequired().addAll(source.getRequired());
        copy.getEnumeration().addAll(source.getEnumeration());
        copy.getExamples().addAll(source.getExamples());
        copy.getTuple().addAll(source.getTuple());
        return copy;
    }
}
