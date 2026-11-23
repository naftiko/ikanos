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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.restlet.Context;

import io.ikanos.engine.util.InputDefaults;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.DefinitionsSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterListOrMapDeserializer;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.StructureSpec;

/**
 * Expands the shared {@code definitions} of MCP adapters, REST adapters and aggregates into the
 * tools, resources, operations and flows that use them (#910, #1000).
 *
 * <p>An MCP adapter, a REST adapter or an aggregate declares named {@code inputParameterSets}
 * and {@code schemas} in its {@code definitions} block. They are visible to that namespace
 * only:</p>
 * <ul>
 *   <li>On a REST adapter, {@code inputParameterSets} apply to resources (whose inputs their
 *       operations inherit) and to operations, and set entries are REST inputs ({@code in},
 *       {@code type}, {@code pattern}, {@code value}). REST inputs are flat, so its schemas are
 *       used in orchestrated outputs only: {@code schema:} on a REST input is an error.</li>
 *   <li>{@code inputParameterSets: [names]} on an MCP tool or a flow applies the sets in order,
 *       then the unit's own {@code inputParameters}. An own entry replaces the set's entry with
 *       the same name whole, at the set entry's position. Two sets of one unit must not declare
 *       the same parameter.</li>
 *   <li>{@code schema: name} on an input (top level, {@code items}, {@code properties}), inside
 *       another schema, and in orchestrated (steps-mode) outputs, is replaced by a copy of the
 *       schema. Beside {@code schema:} an input only takes {@code description} (which replaces
 *       the schema's) and, at the top level, {@code required} and {@code default}; an output
 *       takes no other key.</li>
 *   <li>In an output, a schema gives only what an orchestrated output can declare: its
 *       {@code type} ({@code integer} becomes {@code number}), its {@code properties}, and its
 *       {@code items} when they are objects with properties. Input-only keywords
 *       ({@code description}, {@code enum}, {@code format}, {@code minimum}, {@code maximum},
 *       {@code required}, {@code additionalProperties}) do not apply to an output.</li>
 *   <li>In a mapped (call-mode) output, its {@code items} or a property, {@code schema:} comes
 *       with {@code mappings} (#1036): each key a path in the schema (property names joined by
 *       {@code .}, {@code []} for an array's items), each value the {@code mapping} that node
 *       carries inline. The use expands to the inline mapped output, with items of any type;
 *       beside {@code schema:} it takes only {@code mappings} and {@code mapping}, where the
 *       whole value is read. Every scalar, array and object without properties must be
 *       bound, and a binding the runtime never reads (object items, below nested arrays)
 *       is rejected.</li>
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
 * beside {@code schema:}, {@code schema:} or {@code mappings} in a mock output or an MCP
 * resource output, and in a mapped output a binding that leaves a scalar, array or object
 * without properties unbound, names a path the schema does not have, binds what the runtime
 * never reads (object items, below nested arrays, a {@code mapping} on an output object), binds
 * a value that is not a JSONPath, or comes without {@code schema:}. Warning: a
 * definition that no unit uses. Each definition's content is checked once, as declared, without
 * expanding it. Expansion is bounded by {@link #MAX_EXPANDED_NODES} declarations emitted per
 * namespace (an inline declaration counts one, a schema use or a set entry the size of the copy
 * it emits, charged before the copy is made: for an orchestrated output, the size of the output
 * shape it gives, without the branches an output does not declare), so a document cannot
 * multiply itself without limit.</p>
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
     * Expand the definitions of every MCP adapter, REST adapter and aggregate of a document, in
     * the capability and at the root of a standalone file, and report what is wrong.
     *
     * @param spec the document; its tools, resources, operations and flows are rewritten in place
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
                for (Map.Entry<String, McpServerResourceSpec> resource
                        : mcp.getResources().entrySet()) {
                    ns.rejectResourceSchemas(resource.getKey(), resource.getValue());
                }
                ns.finish();
            } else if (server instanceof RestServerSpec rest) {
                Namespace ns = new Namespace("REST adapter '" + rest.getNamespace() + "'",
                        "resource or operation", rest.getDefinitions(), errors, warnings, false);
                for (Map.Entry<String, RestServerResourceSpec> resource
                        : rest.getResources().entrySet()) {
                    ns.expandResource(resource.getKey(), resource.getValue());
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
     * The expansion state of one namespace (MCP adapter, REST adapter or aggregate): its
     * definitions, which of them are used, the schemas that form a cycle, and the expansion
     * budget.
     */
    static final class Namespace {

        private final String owner;
        private final String unitKind;
        private final boolean inputSchemas;
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
        private final Map<String, Long> outputSizes = new HashMap<>();
        private final Map<InputParameterSpec, Long> entrySizes = new IdentityHashMap<>();
        private long expandedNodes;
        private boolean overBudget;

        Namespace(String owner, String unitKind, DefinitionsSpec definitions, Set<String> errors,
                Set<String> warnings) {
            this(owner, unitKind, definitions, errors, warnings, true);
        }

        /**
         * @param inputSchemas whether the inputs of this namespace may use {@code schema:}. REST
         *                     inputs are flat ({@code in}, {@code type}, {@code pattern}), so a
         *                     REST adapter uses its schemas in orchestrated outputs only.
         */
        Namespace(String owner, String unitKind, DefinitionsSpec definitions, Set<String> errors,
                Set<String> warnings, boolean inputSchemas) {
            this.owner = owner;
            this.unitKind = unitKind;
            this.inputSchemas = inputSchemas;
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
            // The map form is a mock tool's, whose outputs are static values: not mapped
            List<OutputParameterSpec> outputs = expandOutputs(location, orchestrated,
                    !orchestrated && !tool.isOutputParametersKeyed(),
                    tool.getOutputParameters());
            if (tool.isOutputParametersKeyed()) {
                // The map form marks a mock tool: keep it, or call inference treats the mock as
                // a tool to infer a call for.
                List<OutputParameterSpec> keyed =
                        new OutputParameterListOrMapDeserializer.KeyedList();
                keyed.addAll(outputs);
                outputs = keyed;
            }
            tool.setOutputParameters(outputs);
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
            // A flow with neither call nor steps is a mock, whose outputs are static values
            flow.setOutputParameters(expandOutputs(location, orchestrated,
                    !orchestrated && flow.getCall() != null, flow.getOutputParameters()));
        }

        /**
         * Expand a REST resource, then its operations. The resource's sets become its own
         * {@code inputParameters}, which its operations inherit as before.
         */
        void expandResource(String key, RestServerResourceSpec resource) {
            String name = resource.getName() != null ? resource.getName() : key;
            String location = owner + ", resource '" + name + "'";
            Map<String, InputParameterSpec> own = new LinkedHashMap<>();
            for (InputParameterSpec param : resource.getInputParameters()) {
                own.put(param.getName(), param);
            }
            Map<String, InputParameterSpec> inputs =
                    expandInputs(location, resource.getInputParameterSets(), own);
            if (inputs != null) {
                resource.setInputParameters(inputs);
                resource.setInputParameterSets(List.of());
            }
            for (Map.Entry<String, RestServerOperationSpec> operation
                    : resource.getOperations().entrySet()) {
                expandOperation(location, operation.getKey(), operation.getValue());
            }
        }

        /** Expand a REST operation: its sets, its inputs and its orchestrated outputs. */
        void expandOperation(String resourceLocation, String key,
                RestServerOperationSpec operation) {
            String name = operation.getName() != null ? operation.getName() : key;
            String location = resourceLocation + ", operation '" + name + "'";
            Map<String, InputParameterSpec> own = new LinkedHashMap<>();
            for (InputParameterSpec param : operation.getInputParameters()) {
                own.put(param.getName(), param);
            }
            Map<String, InputParameterSpec> inputs =
                    expandInputs(location, operation.getInputParameterSets(), own);
            if (inputs != null) {
                operation.setInputParameters(inputs);
                operation.setInputParameterSets(List.of());
            }
            boolean orchestrated = operation.getSteps() != null
                    && !operation.getSteps().isEmpty();
            // An operation with neither call nor steps is a mock, whose outputs are static values
            operation.setOutputParameters(expandOutputs(location, orchestrated,
                    !orchestrated && operation.getCall() != null,
                    operation.getOutputParameters()));
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
                    String location = setLocation(set.getKey(), param.getName());
                    checkContent(param, location, true);
                    checkDefault(param, location);
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
         * Check the default of a set entry against the type and enum it resolves to, through its
         * {@code schema:} reference if any, where the set declares it: an entry that no unit
         * emits (its set is unused, or every unit replaces it) is never checked on a tool or
         * flow input, and its default must not wait for its first use either. An untyped entry
         * is a {@code string}, as it is once emitted on a tool or flow.
         */
        private void checkDefault(InputParameterSpec param, String location) {
            InputParameterSpec resolved = param != null ? target(param) : null;
            if (resolved == null || param.getDefaultValue() == null
                    || resolved.getSchema() != null) {
                // A reference that does not expand is reported by checkContent
                return;
            }
            String type = resolved.getType() != null ? resolved.getType()
                    : InputParameterSpec.DEFAULT_MCP_TOOL_INPUT_TYPE;
            String error = InputDefaults.defaultError(param.getDefaultValue(), type,
                    resolved.getEnumeration());
            if (error != null) {
                errors.add(location + ": " + error + ".");
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
                        if (own.containsKey(param.getName())) {
                            // Replaced whole by the unit's own entry: nothing to emit.
                            merged.put(param.getName(), null);
                            continue;
                        }
                        // Charge the copy before making it: past the budget, nothing is copied.
                        if (!withinBudget(entrySize(param))) {
                            break;
                        }
                        merged.put(param.getName(), instantiateNode(param));
                    }
                }
            }
            merged.putAll(own);
            Map<String, InputParameterSpec> expanded = new LinkedHashMap<>();
            for (Map.Entry<String, InputParameterSpec> entry : merged.entrySet()) {
                String key = entry.getKey();
                if (own.containsKey(key)) {
                    expanded.put(key, expandInput(entry.getValue(),
                            location + ", input '" + key + "'", true));
                } else {
                    // A set entry, already expanded and charged; its content is checked once,
                    // at the set, by finish().
                    expanded.put(key, entry.getValue());
                }
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
            if (param.getSchema() != null && !inputSchemas) {
                rejectInputSchema(param, location);
                return param;
            }
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
         * Expand the schema references of the outputs of a unit. An orchestrated output takes
         * {@code schema:} alone; a mapped (call-mode) output takes it with {@code mappings}, the
         * mapping of each path of the schema (#1036). Mock outputs, static values, take none.
         *
         * @param mapped whether the outputs are mapped (call-mode) outputs
         */
        List<OutputParameterSpec> expandOutputs(String location, boolean orchestrated,
                boolean mapped, List<OutputParameterSpec> outputs) {
            List<OutputParameterSpec> expanded = new ArrayList<>();
            if (outputs == null) {
                return expanded;
            }
            for (OutputParameterSpec output : outputs) {
                String where = output.getName() != null
                        ? location + ", output '" + output.getName() + "'"
                        : location + ", output";
                if (orchestrated) {
                    checkNoMappings(output, where);
                    expanded.add(expandOutput(output, where));
                } else if (mapped) {
                    expanded.add(expandMappedOutput(output, where, Position.TOP));
                } else {
                    if (referencesSchema(output)) {
                        errors.add(where + ": 'schema:' is not allowed in a mock output, which"
                                + " returns static values. Declare the output inline.");
                    }
                    checkJsonSchemaRefs(output, where);
                    expanded.add(output);
                }
            }
            return expanded;
        }

        /**
         * Report {@code schema:} or {@code mappings} anywhere in the outputs of an MCP resource:
         * definitions do not reach resources, so the reference would never be expanded.
         */
        void rejectResourceSchemas(String key, McpServerResourceSpec resource) {
            String name = resource.getName() != null ? resource.getName() : key;
            for (OutputParameterSpec output : resource.getOutputParameters()) {
                if (referencesSchema(output) || hasMappings(output)) {
                    errors.add(owner + ", resource '" + name + "': 'schema:' and 'mappings' are"
                            + " not supported in an MCP resource output. Declare the output"
                            + " inline.");
                    return;
                }
            }
        }

        /** Report {@code mappings} anywhere in an orchestrated output: steps bind its values. */
        private void checkNoMappings(OutputParameterSpec param, String location) {
            if (param == null) {
                return;
            }
            if (!param.getMappings().isEmpty() && param.getSchema() == null) {
                errors.add(location + ": 'mappings' is only allowed beside 'schema:' in a mapped"
                        + " (call-mode) output. An orchestrated output is filled by the unit's"
                        + " step 'mappings'.");
            }
            checkNoMappings(param.getItems(), location + ", items");
            for (OutputParameterSpec property : param.getProperties()) {
                checkNoMappings(property, location + ", property '" + property.getName() + "'");
            }
        }

        /**
         * Where a mapped output sits, which decides how the runtime reads it
         * ({@code Resolver.resolveOutputMappings}).
         */
        enum Position {
            /** A unit's output: an object reads the response root, whatever its mapping. */
            TOP,
            /**
             * The items (or map values) of an array: each element is the root; items that are
             * objects with properties ignore their own mapping.
             */
            ITEMS,
            /** A property: its mapping selects the value, or its parent's root when absent. */
            PROPERTY,
            /**
             * Inside the items of an array that is itself items: the runtime passes those
             * elements through as they are, so nothing below is read.
             */
            UNREAD
        }

        /**
         * Expand the schema references of a mapped output, in place, and return it, or the
         * mapped output a reference binds. Each inline declaration costs one unit of the
         * expansion budget, and each reference the size of the schema it binds.
         *
         * @param position where the output sits
         */
        OutputParameterSpec expandMappedOutput(OutputParameterSpec param, String location,
                Position position) {
            if (param == null || overBudget) {
                return param;
            }
            checkJsonSchemaRef(param, location);
            if (param.getSchema() != null) {
                return bindSchema(param, location, position);
            }
            if (!param.getMappings().isEmpty()) {
                errors.add(location + ": 'mappings' binds a schema, so it needs 'schema:' beside"
                        + " it. Name the schema, or declare a 'mapping' on each leaf inline.");
            }
            if (!withinBudget(1)) {
                return param;
            }
            boolean unread = position == Position.UNREAD;
            if (param.getItems() != null) {
                // The items of an array that is itself items are passed through as they are
                boolean nested = position == Position.ITEMS && "array".equals(param.getType());
                param.setItems(expandMappedOutput(param.getItems(), location + ", items",
                        unread || nested ? Position.UNREAD : Position.ITEMS));
            }
            if (param.getValues() != null) {
                param.setValues(expandMappedOutput(param.getValues(), location + ", values",
                        unread ? Position.UNREAD : Position.ITEMS));
            }
            List<OutputParameterSpec> properties = param.getProperties();
            for (int i = 0; i < properties.size(); i++) {
                OutputParameterSpec property = properties.get(i);
                properties.set(i, expandMappedOutput(property,
                        location + ", property '" + property.getName() + "'",
                        unread ? Position.UNREAD : Position.PROPERTY));
            }
            return param;
        }

        /**
         * Bind a schema reference of a mapped output: build the inline mapped output the schema
         * gives, with each path's mapping from {@code mappings}, and report the paths left
         * unbound, the paths the schema does not have, the bindings the runtime never reads,
         * and the values that are not JSONPaths.
         */
        private OutputParameterSpec bindSchema(OutputParameterSpec use, String location,
                Position position) {
            checkMappedBesideSchema(use, location);
            String name = use.getSchema();
            if (position == Position.UNREAD) {
                if (lookupSchema(name, location) != null) {
                    markUsed(name);
                }
                errors.add(location + ": 'schema: " + name + "' sits in the items of a nested"
                        + " array, which the runtime passes through as they are: its bindings"
                        + " are never read. Remove it, or bind the outer items instead.");
                return use;
            }
            if (!resolvable(name, location) || !withinBudget(size(name))) {
                return use;
            }
            InputParameterSpec root = target(schemas.get(name));
            boolean object = "object".equals(root.getType());
            boolean withProperties = isObjectWithProperties(root);
            if (position == Position.TOP && object && !withProperties) {
                errors.add(location + ": 'schema: " + name + "' is an object without properties,"
                        + " which as an output always returns an empty object. Use it as a"
                        + " property, with a 'mapping'.");
                return use;
            }
            Binding binding = new Binding(use.getMappings(),
                    position == Position.ITEMS && "array".equals(root.getType()));
            OutputParameterSpec output = binding.bind(schemas.get(name), "", false);
            output.setName(use.getName());
            if (use.getMappings().isEmpty() && !binding.unbound.isEmpty()) {
                errors.add(location + ": 'schema: " + name + "' in a mapped (call-mode) output"
                        + " needs 'mappings', the mapping of each path of the schema.");
                return use;
            }
            if (use.getMapping() != null && object && position != Position.PROPERTY
                    && (withProperties || position == Position.TOP)) {
                // The runtime reads an output object from the response root, and object items
                // from each element, whatever their mapping
                errors.add(location + ": 'mapping' beside 'schema: " + name + "' has no effect:"
                        + " " + (position == Position.TOP ? "an output object is read from the"
                        + " response root" : "object items are read from each element")
                        + ". Remove it, or move the object under a property.");
            } else {
                output.setMapping(use.getMapping());
            }
            // Unbound, an object with properties reads its parent's root and items each
            // element, as inline; anything else is read through its own mapping
            if (use.getMapping() == null && position != Position.ITEMS && !withProperties) {
                binding.unbound.add(0, "(the output itself: set 'mapping' beside 'schema:')");
            }
            binding.report(location, name);
            return output;
        }

        /**
         * The binding of one schema reference: its {@code mappings}, which of them are used, and
         * what is wrong with them.
         */
        private final class Binding {

            private final Map<String, String> mappings;
            private final Set<String> used = new HashSet<>();
            private final List<String> paths = new ArrayList<>();
            private final List<String> unbound = new ArrayList<>();
            private final List<String> unaddressable = new ArrayList<>();
            private final List<String> ineffective = new ArrayList<>();
            private final boolean rootIsItems;

            /**
             * @param rootIsItems whether the bound schema is an array used as the items of
             *                    another array, whose own items the runtime never shapes
             */
            Binding(Map<String, String> mappings, boolean rootIsItems) {
                this.mappings = mappings;
                this.rootIsItems = rootIsItems;
            }

            /**
             * The mapped output a declaration gives at a path of the schema: its type
             * ({@code integer} becomes {@code number}), the mapping bound to the path, its
             * properties and its items, of any type. Input-only keywords are not carried.
             *
             * @param unread whether the runtime never reads this node: the items of an array
             *               that is itself items, which it passes through as they are
             */
            OutputParameterSpec bind(InputParameterSpec declaration, String path,
                    boolean unread) {
                InputParameterSpec schema = target(declaration);
                OutputParameterSpec output = new OutputParameterSpec();
                output.setName(declaration.getName());
                String type = schema.getType();
                output.setType("integer".equals(type) ? "number" : type);
                boolean withProperties = isObjectWithProperties(schema);
                if (!path.isEmpty()) {
                    String mapping = mappings.get(path);
                    if (mapping != null) {
                        used.add(path);
                    }
                    if (unread) {
                        addIf(ineffective, path, mapping != null);
                    } else if (mapping != null && path.endsWith("[]") && withProperties) {
                        // Object items are read from each element, whatever their mapping
                        paths.add(path);
                        ineffective.add(path);
                    } else {
                        paths.add(path);
                        output.setMapping(mapping);
                        // Unbound, an object with properties reads its parent's root and
                        // items each element; anything else would always be null
                        addIf(unbound, path, mapping == null && !withProperties
                                && !path.endsWith("[]"));
                    }
                }
                if ("object".equals(type)) {
                    for (InputParameterSpec property : schema.getProperties()) {
                        String propertyName = property.getName();
                        if (propertyName.contains(".") || propertyName.contains("[")
                                || propertyName.contains("]")) {
                            unaddressable.add(propertyName);
                            continue;
                        }
                        output.getProperties().add(bind(property,
                                path.isEmpty() ? propertyName : path + "." + propertyName,
                                unread));
                    }
                } else if ("array".equals(type) && schema.getItems() != null) {
                    boolean itemsUnread = unread || path.endsWith("[]")
                            || (path.isEmpty() && rootIsItems);
                    OutputParameterSpec itemsOutput = bind(schema.getItems(), path + "[]",
                            itemsUnread);
                    itemsOutput.setName(null);
                    output.setItems(itemsOutput);
                }
                return output;
            }

            void report(String location, String schema) {
                String prefix = location + ": 'schema: " + schema + "' ";
                if (!unaddressable.isEmpty()) {
                    errors.add(prefix + "has properties that a path cannot address, as their"
                            + " name contains '.', '[' or ']': " + unaddressable + ". Declare"
                            + " the output inline.");
                }
                if (!unbound.isEmpty()) {
                    errors.add(prefix + "leaves paths unbound: " + unbound + ". Give each"
                            + " scalar, array and object without properties a mapping in"
                            + " 'mappings'.");
                }
                if (!ineffective.isEmpty()) {
                    errors.add(prefix + "binds " + ineffective + ", which the runtime never"
                            + " reads: object items are read from each element, and the items"
                            + " of nested arrays are passed through as they are. Remove them.");
                }
                List<String> unknown = new ArrayList<>();
                List<String> notPaths = new ArrayList<>();
                for (Map.Entry<String, String> entry : mappings.entrySet()) {
                    if (!used.contains(entry.getKey())) {
                        unknown.add(entry.getKey());
                    } else if (!entry.getValue().trim().startsWith("$")) {
                        notPaths.add(entry.getKey());
                    }
                }
                if (!unknown.isEmpty()) {
                    errors.add(prefix + "has no path " + unknown + ". Its paths are "
                            + (paths.isEmpty() ? "none" : String.join(", ", paths)) + ".");
                }
                if (!notPaths.isEmpty()) {
                    errors.add(prefix + "binds " + notPaths + " to a value that is not a"
                            + " JSONPath (starting with '$'). A static 'value' is declared"
                            + " inline.");
                }
            }
        }

        OutputParameterSpec expandOutput(OutputParameterSpec param, String location) {
            if (param == null || overBudget) {
                return param;
            }
            checkJsonSchemaRef(param, location);
            if (param.getSchema() != null) {
                checkOutputBesideSchema(param, location);
                OutputParameterSpec output = resolveOutputSchema(param.getSchema(), location);
                if (output == null) {
                    return param;
                }
                output.setName(param.getName());
                return output;
            }
            if (!withinBudget(1)) {
                return param;
            }
            if (param.getItems() != null) {
                OutputParameterSpec items = param.getItems();
                param.setItems(items.getSchema() != null
                        ? expandItemsReference(items, location + ", items")
                        : expandOutput(items, location + ", items"));
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
         * Expand {@code items: { schema: name }} of an orchestrated array output. It gives what
         * a whole-array schema gives its items: only an object with properties, the only items
         * an orchestrated output declares. Other schemas give no items, at no cost to the
         * budget; their content is still checked.
         */
        private OutputParameterSpec expandItemsReference(OutputParameterSpec items,
                String location) {
            if (overBudget) {
                return items;
            }
            checkJsonSchemaRef(items, location);
            checkOutputBesideSchema(items, location);
            String name = items.getSchema();
            if (!resolvable(name, location)) {
                return items;
            }
            if (!isObjectWithProperties(target(schemas.get(name)))) {
                return null;
            }
            if (!withinBudget(outputSize(name))) {
                return items;
            }
            OutputParameterSpec output = outputOf(schemas.get(name));
            output.setName(items.getName());
            return output;
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
            if (usedSets.add(name)) {
                // Its entries are expanded without resolveSchema: mark their schemas used here.
                Set<String> references = new LinkedHashSet<>();
                for (InputParameterSpec param : set.values()) {
                    collectReferences(param, references);
                }
                for (String reference : references) {
                    if (schemas.containsKey(reference)) {
                        markUsed(reference);
                    }
                }
            }
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
            if (!resolvable(name, location) || !withinBudget(size(name))) {
                return null;
            }
            return instantiate(name);
        }

        /**
         * Resolve a schema name used by an orchestrated output into the output shape it gives.
         * The use costs the declarations of that shape only: the branches an output does not
         * declare (input-only keywords, items that are not objects with properties) are neither
         * charged nor materialized. The schema's content is still checked in full.
         *
         * @return the output, or {@code null} when the name cannot be resolved or the output
         *         would exceed the expansion budget
         */
        OutputParameterSpec resolveOutputSchema(String name, String location) {
            if (!resolvable(name, location) || !withinBudget(outputSize(name))) {
                return null;
            }
            return outputOf(schemas.get(name));
        }

        /**
         * Look a schema up, mark it used, check its content once, and tell whether its
         * expansion terminates (no cycle, within the depth bound).
         */
        private boolean resolvable(String name, String location) {
            if (lookupSchema(name, location) == null) {
                return false;
            }
            markUsed(name);
            checkSchema(name);
            return !cyclic.contains(name) && !tooDeep.contains(name);
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
            if (param.getSchema() != null && !inputSchemas && topLevel) {
                rejectInputSchema(param, location);
                return;
            }
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
         * Number of parameter declarations a set entry emits once expanded, computed once per
         * entry, whatever the number of units that use its set, without materializing it.
         */
        private long entrySize(InputParameterSpec param) {
            return entrySizes.computeIfAbsent(param, this::sizeOf);
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

        /**
         * Number of declarations of the orchestrated output a schema gives, computed from the
         * declarations without materializing them, and capped just past the budget.
         */
        long outputSize(String name) {
            Long known = outputSizes.get(name);
            if (known == null) {
                known = outputSizeOf(schemas.get(name));
                outputSizes.put(name, known);
            }
            return known;
        }

        private long outputSizeOf(InputParameterSpec param) {
            if (param == null) {
                return 0;
            }
            if (param.getSchema() != null) {
                return expandable(param.getSchema()) ? outputSize(param.getSchema()) : 1;
            }
            long total = 1;
            if ("object".equals(param.getType())) {
                for (InputParameterSpec property : param.getProperties()) {
                    total = Math.min(total + outputSizeOf(property), MAX_EXPANDED_NODES + 1L);
                }
            } else if ("array".equals(param.getType())
                    && isObjectWithProperties(target(param.getItems()))) {
                total = Math.min(total + outputSizeOf(param.getItems()),
                        MAX_EXPANDED_NODES + 1L);
            }
            return total;
        }

        /**
         * The orchestrated output a declaration gives once expanded: its type ({@code integer}
         * becomes {@code number}, as orchestrated outputs have no integer), its properties, and
         * its items when they are objects with properties (the only items an orchestrated
         * output declares). Built directly from the declarations, following schema references.
         */
        OutputParameterSpec outputOf(InputParameterSpec declaration) {
            InputParameterSpec schema = target(declaration);
            OutputParameterSpec output = new OutputParameterSpec();
            output.setName(declaration.getName());
            String type = schema.getType();
            output.setType("integer".equals(type) ? "number" : type);
            if ("object".equals(type)) {
                for (InputParameterSpec property : schema.getProperties()) {
                    output.getProperties().add(outputOf(property));
                }
            } else if ("array".equals(type)) {
                InputParameterSpec items = schema.getItems();
                if (isObjectWithProperties(target(items))) {
                    OutputParameterSpec itemsOutput = outputOf(items);
                    itemsOutput.setName(null);
                    output.setItems(itemsOutput);
                }
            }
            return output;
        }

        /**
         * The declaration a reference stands for, following references that expand; a
         * declaration that is not a reference, or whose reference does not expand, stands for
         * itself.
         */
        private InputParameterSpec target(InputParameterSpec declaration) {
            InputParameterSpec current = declaration;
            while (current != null && current.getSchema() != null
                    && expandable(current.getSchema())) {
                current = schemas.get(current.getSchema());
            }
            return current;
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
                    return source.copy();
                }
                InputParameterSpec resolved = instantiate(source.getSchema());
                applyUseSite(source, resolved);
                return resolved;
            }
            InputParameterSpec node = source.copyNode();
            node.setItems(instantiateNode(source.getItems()));
            node.setValues(instantiateNode(source.getValues()));
            for (InputParameterSpec property : source.getProperties()) {
                node.getProperties().add(instantiateNode(property));
            }
            return node;
        }

        /**
         * Give an expanded schema the name, {@code required}, description and {@code default} of
         * its use.
         */
        private static void applyUseSite(InputParameterSpec use, InputParameterSpec resolved) {
            resolved.setName(use.getName());
            resolved.setDeclaredRequired(use.getDeclaredRequired());
            if (use.getDescription() != null) {
                resolved.setDescription(use.getDescription());
            }
            if (use.getDefaultValue() != null) {
                resolved.setDefaultValue(use.getDefaultValue().deepCopy());
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
                    + " Definitions are local to their adapter or aggregate and referenced"
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
            // A nested input takes no required flag either, true or false: only a top-level
            // input is required or not, and the flag would not reach the advertised schema
            addIf(extra, topLevel ? "requiredProperties" : "required",
                    !param.getRequired().isEmpty()
                            || (!topLevel && param.getDeclaredRequired() != null));
            // Like required, a default belongs to a top-level input only (#979)
            addIf(extra, "default", !topLevel && param.getDefaultValue() != null);
            addIf(extra, "enum", !param.getEnumeration().isEmpty());
            addIf(extra, "examples", !param.getExamples().isEmpty());
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
                        + " than 'description'" + (topLevel ? ", 'required' and 'default'" : "")
                        + ", found " + extra + ". Change the schema, or declare the input"
                        + " inline.");
            }
        }

        /**
         * Report {@code schema:} on an input of a namespace whose inputs are flat (a REST
         * adapter). A schema it names is still marked used, so it is not also reported unused.
         */
        private void rejectInputSchema(InputParameterSpec param, String location) {
            if (schemas.containsKey(param.getSchema())) {
                markUsed(param.getSchema());
            }
            errors.add(location + ": 'schema:' is not supported on a REST input, which is flat"
                    + " ('in', 'type', 'pattern'). Declare the input inline: a REST adapter uses"
                    + " its schemas in orchestrated (steps) outputs.");
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
            // The keys as declared, including one the deserializer does not model (`format`)
            // or whose value it ignores (an `enum` that is not a list)
            for (String key : param.getSchemaSiblings().keySet()) {
                addIf(extra, key, !extra.contains(key));
            }
            if (!extra.isEmpty()) {
                errors.add(location + ": 'schema: " + param.getSchema() + "' takes no other key"
                        + " in an output, found " + extra + ".");
            }
        }

        /**
         * Report the keys beside {@code schema:} in a mapped output other than {@code mappings}
         * (its binding) and {@code mapping} (where the whole value is read).
         */
        private void checkMappedBesideSchema(OutputParameterSpec param, String location) {
            List<String> extra = new ArrayList<>();
            for (String key : param.getSchemaSiblings().keySet()) {
                addIf(extra, key, !"mappings".equals(key) && !"mapping".equals(key));
            }
            // Keys set on the model rather than declared (a copy built in code)
            addIf(extra, "type", param.getType() != null && !extra.contains("type"));
            addIf(extra, "items", param.getItems() != null && !extra.contains("items"));
            addIf(extra, "properties", !param.getProperties().isEmpty()
                    && !extra.contains("properties"));
            if (!extra.isEmpty()) {
                errors.add(location + ": 'schema: " + param.getSchema() + "' takes only"
                        + " 'mappings' and 'mapping' in a mapped output, found " + extra + ".");
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


    /** Whether an output, or one of its nested declarations, declares {@code mappings}. */
    static boolean hasMappings(OutputParameterSpec param) {
        if (param == null) {
            return false;
        }
        if (!param.getMappings().isEmpty() || hasMappings(param.getItems())
                || hasMappings(param.getValues())) {
            return true;
        }
        for (OutputParameterSpec property : param.getProperties()) {
            if (hasMappings(property)) {
                return true;
            }
        }
        return false;
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
        return param != null && "object".equals(param.getType())
                && !param.getProperties().isEmpty();
    }
}
