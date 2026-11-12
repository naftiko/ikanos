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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

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
import io.ikanos.spec.util.StructureSpec;

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
 * <p>Errors: an unknown reference, a reference of the wrong kind, a qualified ({@code a.b})
 * reference, a JSON Schema {@code $ref} (rejected by its presence, alone or beside
 * {@code schema:}), the same parameter from two sets of one unit, a cycle between schemas, keys
 * beside {@code schema:}, and {@code schema:} in an output that is not orchestrated. Warning: a
 * definition that no unit uses. Each definition's content is checked once, as declared, without
 * expanding it. Expansion is bounded by {@link #MAX_EXPANDED_NODES} declarations emitted per
 * namespace (an inline declaration counts one, a schema use the size of the copy it emits), so
 * a document cannot multiply itself without limit.</p>
 */
public final class DefinitionsExpander {

    /**
     * Upper bound on the parameter declarations that the tools or flows of one namespace may
     * emit once their definitions are expanded. Schemas that reference each other without a
     * cycle can still grow exponentially (two properties of the same schema at every level), so
     * expansion stops past this bound. Checking the definitions themselves costs nothing.
     */
    public static final int MAX_EXPANDED_NODES = 100_000;

    /**
     * Upper bound on the nesting depth of an expanded schema: its own nested declarations plus
     * those of every schema it references, directly or not. It keeps a long chain of schemas
     * that use each other from overflowing the stack of the recursive expansion.
     */
    public static final int MAX_EXPANDED_DEPTH = 64;

    /** A bare definition name: no qualifier, no pointer. */
    private static final Pattern BARE_NAME = Pattern.compile("[a-zA-Z0-9-]+");

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
        private final Set<String> tooDeep = new HashSet<>();
        private final Set<String> checkedSchemas = new HashSet<>();
        private final Map<String, Long> sizes = new HashMap<>();
        private long expandedNodes;
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
         * Check the content of every definition, then report the definitions that no unit
         * uses. A definition that is never used is still checked, so a mistake in it does not
         * wait for its first use. Checking walks each definition once, as declared: it never
         * materializes an expansion, so it does not consume the expansion budget.
         */
        void finish() {
            for (Map.Entry<String, Map<String, InputParameterSpec>> set : sets.entrySet()) {
                for (InputParameterSpec param : set.getValue().values()) {
                    checkContent(param, setLocation(set.getKey(), param.getName()), true);
                }
            }
            for (String name : schemas.keySet()) {
                checkSchema(name);
            }
            for (String name : sets.keySet()) {
                if (!usedSets.contains(name)) {
                    warnings.add(owner + " declares the input parameter set '" + name
                            + "', which no " + unitKind + " uses.");
                }
            }
            for (String name : schemas.keySet()) {
                if (!usedSchemas.contains(name)) {
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
                usesSchemas |= referencesSchema(param) || hasJsonSchemaRef(param);
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
         * schema copy that replaces it. Each inline declaration costs one unit of the expansion
         * budget, and each schema reference the size of the expanded schema it emits.
         *
         * @param topLevel whether the input is a tool or flow input, which also takes
         *                 {@code required} beside {@code schema:}
         */
        InputParameterSpec expandInput(InputParameterSpec param, String location,
                boolean topLevel) {
            if (param == null || overBudget) {
                return param;
            }
            checkJsonSchemaRef(param, location);
            if (param.getSchema() != null) {
                checkInputBesideSchema(param, location, topLevel);
                InputParameterSpec resolved = resolveSchema(param.getSchema(), location);
                if (resolved == null) {
                    return param;
                }
                applyUseSite(param, resolved);
                return resolved;
            }
            if (!withinBudget(1)) {
                return param;
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
                    checkJsonSchemaRefs(output, where);
                    expanded.add(output);
                }
            }
            return expanded;
        }

        OutputParameterSpec expandOutput(OutputParameterSpec param, String location) {
            if (param == null || overBudget) {
                return param;
            }
            checkJsonSchemaRef(param, location);
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
            if (!withinBudget(1)) {
                return param;
            }
            if (param.getItems() != null) {
                OutputParameterSpec items = param.getItems();
                OutputParameterSpec expanded = expandOutput(items, location + ", items");
                // `items: { schema: name }` gives what a whole-array schema gives its items:
                // only objects with properties, the only items an orchestrated output declares.
                if (items.getSchema() != null && expanded != items
                        && !isObjectWithProperties(expanded)) {
                    expanded = null;
                }
                param.setItems(expanded);
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
         * Resolve a schema name used by a unit into a fresh, fully expanded copy of the schema.
         * The schema's content is checked once, whatever the number of its uses, and the use
         * costs the size of the copy it emits.
         *
         * @return the copy, or {@code null} when the name cannot be resolved or the copy would
         *         exceed the expansion budget
         */
        InputParameterSpec resolveSchema(String name, String location) {
            if (lookupSchema(name, location) == null) {
                return null;
            }
            markUsed(name);
            checkSchema(name);
            if (cyclic.contains(name) || tooDeep.contains(name)
                    || !withinBudget(size(name))) {
                return null;
            }
            return instantiate(name);
        }

        /**
         * Look a schema name up, reporting a qualified name, an unknown name or a set name.
         */
        private InputParameterSpec lookupSchema(String name, String location) {
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
            }
            return schema;
        }

        /** Mark a schema used, with every schema it references, directly or not. */
        private void markUsed(String name) {
            List<String> pending = new ArrayList<>(List.of(name));
            while (!pending.isEmpty()) {
                String next = pending.remove(pending.size() - 1);
                if (schemas.containsKey(next) && usedSchemas.add(next)) {
                    Set<String> references = new LinkedHashSet<>();
                    collectReferences(schemas.get(next), references);
                    pending.addAll(references);
                }
            }
        }

        /**
         * Check the content of a schema, once, where it is declared, then of the schemas it
         * references, iteratively: a long chain of schemas never deepens the stack.
         */
        void checkSchema(String name) {
            List<String> pending = new ArrayList<>(List.of(name));
            while (!pending.isEmpty()) {
                String next = pending.remove(0);
                if (schemas.containsKey(next) && checkedSchemas.add(next)) {
                    checkContent(schemas.get(next), owner + ", schema '" + next + "'", false,
                            pending);
                }
            }
        }

        /**
         * Check a declaration as written, without expanding it: its {@code $ref}, the keys
         * beside its {@code schema:}, and the schema it references (checked where declared).
         */
        private void checkContent(InputParameterSpec param, String location, boolean topLevel) {
            List<String> referenced = new ArrayList<>();
            checkContent(param, location, topLevel, referenced);
            for (String name : referenced) {
                checkSchema(name);
            }
        }

        /**
         * @param referenced collects the schemas the declaration references, to be checked
         *                   by the caller
         */
        private void checkContent(InputParameterSpec param, String location, boolean topLevel,
                List<String> referenced) {
            if (param == null) {
                return;
            }
            checkJsonSchemaRef(param, location);
            if (param.getSchema() != null) {
                checkInputBesideSchema(param, location, topLevel);
                if (lookupSchema(param.getSchema(), location) != null) {
                    referenced.add(param.getSchema());
                }
                return;
            }
            checkContent(param.getItems(), location + ", items", false, referenced);
            checkContent(param.getValues(), location + ", values", false, referenced);
            for (InputParameterSpec property : param.getProperties()) {
                checkContent(property, location + ", property '" + property.getName() + "'",
                        false, referenced);
            }
        }

        /** Whether a reference resolves to a schema whose expansion terminates. */
        private boolean expandable(String name) {
            return name != null && BARE_NAME.matcher(name).matches()
                    && schemas.containsKey(name) && !cyclic.contains(name)
                    && !tooDeep.contains(name);
        }

        /**
         * Number of parameter declarations the expanded schema emits, computed from the
         * declarations without materializing them, and capped just past the budget.
         */
        long size(String name) {
            Long known = sizes.get(name);
            if (known == null) {
                known = sizeOf(schemas.get(name));
                sizes.put(name, known);
            }
            return known;
        }

        private long sizeOf(InputParameterSpec param) {
            if (param == null) {
                return 0;
            }
            if (param.getSchema() != null) {
                return expandable(param.getSchema()) ? size(param.getSchema()) : 1;
            }
            long total = 1 + sizeOf(param.getItems()) + sizeOf(param.getValues());
            for (InputParameterSpec property : param.getProperties()) {
                total = Math.min(total + sizeOf(property), MAX_EXPANDED_NODES + 1L);
            }
            return Math.min(total, MAX_EXPANDED_NODES + 1L);
        }

        /** A fresh, fully expanded copy of a schema whose content was checked. */
        private InputParameterSpec instantiate(String name) {
            return instantiateNode(schemas.get(name));
        }

        private InputParameterSpec instantiateNode(InputParameterSpec source) {
            if (source == null) {
                return null;
            }
            if (source.getSchema() != null) {
                if (!expandable(source.getSchema())) {
                    return copy(source);
                }
                InputParameterSpec resolved = instantiate(source.getSchema());
                applyUseSite(source, resolved);
                return resolved;
            }
            InputParameterSpec node = copyNode(source);
            node.setItems(instantiateNode(source.getItems()));
            node.setValues(instantiateNode(source.getValues()));
            for (InputParameterSpec property : source.getProperties()) {
                node.getProperties().add(instantiateNode(property));
            }
            return node;
        }

        /** Give an expanded schema the name, {@code required} and description of its use. */
        private static void applyUseSite(InputParameterSpec use, InputParameterSpec resolved) {
            resolved.setName(use.getName());
            resolved.setRequired(use.isRequired());
            if (use.getDescription() != null) {
                resolved.setDescription(use.getDescription());
            }
        }

        private String setLocation(String set, String input) {
            return owner + ", input parameter set '" + set + "', input '" + input + "'";
        }

        private boolean isBareName(String name, String location) {
            if (name != null && BARE_NAME.matcher(name).matches()) {
                return true;
            }
            errors.add(location + ": '" + name + "' is not the bare name of a definition."
                    + " Definitions are local to their MCP adapter or aggregate and referenced"
                    + " by name: qualified names ('a.b') and '$ref' pointers are not supported.");
            return false;
        }

        private void checkJsonSchemaRef(StructureSpec<?> param, String location) {
            if (param.getJsonSchemaRef() != null) {
                errors.add(location + ": '$ref' is not supported. Reference a schema of the"
                        + " definitions by its bare name, with 'schema: name'.");
            }
        }

        private void checkJsonSchemaRefs(StructureSpec<?> param, String location) {
            if (param == null) {
                return;
            }
            checkJsonSchemaRef(param, location);
            checkJsonSchemaRefs(param.getItems(), location + ", items");
            checkJsonSchemaRefs(param.getValues(), location + ", values");
            for (StructureSpec<?> property : param.getProperties()) {
                checkJsonSchemaRefs(property, location + ", property '" + property.getName()
                        + "'");
            }
        }

        /**
         * Charge declarations to the expansion budget of the namespace.
         *
         * @param declarations the number of parameter declarations about to be emitted
         * @return whether they fit; past the budget, the error is reported once
         */
        private boolean withinBudget(long declarations) {
            if (!overBudget && expandedNodes + declarations <= MAX_EXPANDED_NODES) {
                expandedNodes += declarations;
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
            addIf(extra, "const", param.getConstant() != null);
            addIf(extra, "selector", param.getSelector() != null);
            addIf(extra, "required", !param.getRequired().isEmpty());
            addIf(extra, "enum", !param.getEnumeration().isEmpty());
            addIf(extra, "maxLength", param.getMaxLength() != null);
            addIf(extra, "precision", param.getPrecision() != null);
            addIf(extra, "scale", param.getScale() != null);
            addIf(extra, "contentEncoding", param.getContentEncoding() != null);
            addIf(extra, "contentCompression", param.getContentCompression() != null);
            addIf(extra, "contentMediaType", param.getContentMediaType() != null);
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
         * remember its members so that expansion never follows it. Then bound the depth of every
         * other schema ({@link #MAX_EXPANDED_DEPTH}). Both walks are iterative, so a long chain
         * of schemas does not overflow the stack.
         */
        private void findCycles() {
            Map<String, Integer> state = new LinkedHashMap<>();
            List<String> finished = new ArrayList<>();
            for (String name : schemas.keySet()) {
                visit(name, state, finished);
            }
            boundDepth(finished);
        }

        /** Depth-first walk from a schema, recording schemas in the order they finish. */
        private void visit(String root, Map<String, Integer> state, List<String> finished) {
            if (state.containsKey(root)) {
                return;
            }
            List<String> path = new ArrayList<>();
            List<Iterator<String>> pending = new ArrayList<>();
            state.put(root, 1);
            path.add(root);
            pending.add(references(root).iterator());
            while (!pending.isEmpty()) {
                Iterator<String> next = pending.get(pending.size() - 1);
                if (!next.hasNext()) {
                    String done = path.remove(path.size() - 1);
                    pending.remove(pending.size() - 1);
                    state.put(done, 2);
                    finished.add(done);
                    continue;
                }
                String reference = next.next();
                Integer current = state.get(reference);
                if (current == null) {
                    state.put(reference, 1);
                    path.add(reference);
                    pending.add(references(reference).iterator());
                } else if (current == 1) {
                    List<String> cycle = new ArrayList<>(path.subList(path.indexOf(reference),
                            path.size()));
                    cyclic.addAll(cycle);
                    cycle.add(reference);
                    errors.add(owner + ": schemas reference each other in a cycle: "
                            + String.join(" -> ", cycle) + ".");
                }
            }
        }

        /** The schemas of this namespace that a schema references. */
        private Set<String> references(String name) {
            Set<String> references = new LinkedHashSet<>();
            collectReferences(schemas.get(name), references);
            references.retainAll(schemas.keySet());
            return references;
        }

        /**
         * Compute the expanded depth of every schema, in the order they finished (a schema after
         * the ones it references), and report the deepest schema of a chain past the bound
         * once. The schemas that reference it are excluded too, without another error.
         */
        private void boundDepth(List<String> finished) {
            Map<String, Integer> depths = new HashMap<>();
            for (String name : finished) {
                if (cyclic.contains(name)) {
                    continue;
                }
                boolean inheritsTooDeep = false;
                for (String reference : references(name)) {
                    inheritsTooDeep |= tooDeep.contains(reference);
                }
                if (inheritsTooDeep) {
                    tooDeep.add(name);
                    continue;
                }
                int depth = depthOf(schemas.get(name), depths);
                depths.put(name, depth);
                if (depth > MAX_EXPANDED_DEPTH) {
                    tooDeep.add(name);
                    errors.add(owner + ", schema '" + name + "': it expands to more than "
                            + MAX_EXPANDED_DEPTH + " nested levels, through the schemas it"
                            + " uses. Expansion is bounded, so that a long chain of schemas"
                            + " cannot exhaust the stack.");
                }
            }
        }

        /** Nesting depth of a declaration; a reference counts one level plus its schema's depth. */
        private int depthOf(InputParameterSpec param, Map<String, Integer> depths) {
            if (param == null) {
                return 0;
            }
            if (param.getSchema() != null) {
                return 1 + depths.getOrDefault(param.getSchema(), 0);
            }
            int deepest = Math.max(depthOf(param.getItems(), depths),
                    depthOf(param.getValues(), depths));
            for (InputParameterSpec property : param.getProperties()) {
                deepest = Math.max(deepest, depthOf(property, depths));
            }
            return 1 + deepest;
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
            if (items != null && isObjectWithProperties(items)) {
                OutputParameterSpec itemsOutput = toOutput(items);
                itemsOutput.setName(null);
                output.setItems(itemsOutput);
            }
        }
        return output;
    }

    /** Whether an input, or one of its nested declarations, carries a JSON Schema {@code $ref}. */
    static boolean hasJsonSchemaRef(InputParameterSpec param) {
        if (param == null) {
            return false;
        }
        if (param.getJsonSchemaRef() != null || hasJsonSchemaRef(param.getItems())
                || hasJsonSchemaRef(param.getValues())) {
            return true;
        }
        for (InputParameterSpec property : param.getProperties()) {
            if (hasJsonSchemaRef(property)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a declaration is an object with properties: the only orchestrated items. */
    static boolean isObjectWithProperties(StructureSpec<?> param) {
        return "object".equals(param.getType()) && !param.getProperties().isEmpty();
    }

    /** Deep copy of an input declaration, so that every use of a definition owns its copy. */
    static InputParameterSpec copy(InputParameterSpec source) {
        if (source == null) {
            return null;
        }
        InputParameterSpec copy = copyNode(source);
        copy.setItems(copy(source.getItems()));
        copy.setValues(copy(source.getValues()));
        for (InputParameterSpec property : source.getProperties()) {
            copy.getProperties().add(copy(property));
        }
        return copy;
    }

    /** Copy of an input declaration, without its {@code items}, {@code values} and properties. */
    static InputParameterSpec copyNode(InputParameterSpec source) {
        InputParameterSpec copy = new InputParameterSpec();
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setDescription(source.getDescription());
        copy.setSchema(source.getSchema());
        copy.setJsonSchemaRef(source.getJsonSchemaRef());
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
        copy.getRequired().addAll(source.getRequired());
        copy.getEnumeration().addAll(source.getEnumeration());
        copy.getExamples().addAll(source.getExamples());
        copy.getTuple().addAll(source.getTuple());
        return copy;
    }
}
