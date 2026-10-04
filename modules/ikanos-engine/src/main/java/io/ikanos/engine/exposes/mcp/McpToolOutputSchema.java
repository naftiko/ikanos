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
package io.ikanos.engine.exposes.mcp;

import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the MCP tool {@code outputSchema} from a tool's declared {@code outputParameters}.
 *
 * <p>The MCP specification requires that, when a tool advertises an {@code outputSchema}, every
 * successful {@code tools/call} result carries a {@code structuredContent} object that conforms
 * to it. The schema is therefore only derived for result paths that are guaranteed to produce a
 * JSON object, and it mirrors what the runtime actually emits:</p>
 *
 * <ul>
 *   <li><b>{@link Source#CALL}</b> — a single {@code call} with exactly one root output parameter
 *       of type {@code object} with {@code properties}.
 *       {@code OperationStepExecutor#applyOutputMappings} returns the first non-null mapped value,
 *       so several root parameters, or a non-object root, cannot be described as one object.</li>
 *   <li><b>{@link Source#STEPS}</b> — orchestrated {@code steps} with {@code mappings}; the result
 *       is the object assembled by {@code OperationStepExecutor#resolveStepMappings}, keyed by the
 *       declared (named) output parameters.</li>
 * </ul>
 *
 * <p>Mock tools (no {@code call}, no {@code steps}) are not described: mock values are always
 * emitted as strings regardless of the declared type, so a typed schema would not match.</p>
 *
 * <h2>Call mode: shaped, keyed output</h2>
 *
 * <p>In call mode {@code Resolver#resolveOutputMappings} <em>shapes</em> the result from the
 * declared parameters (see {@code Resolver#resolveNestedProperty}), so the schema describes that
 * shape in depth:</p>
 * <ul>
 *   <li>every assembled object (the root, a nested object with {@code properties}, an array item
 *       object) always carries <b>every</b> declared key — unresolved values are emitted as
 *       {@code null} — so all of its properties are listed as {@code required};</li>
 *   <li>a mapped {@code array} with {@code items} advertises its {@code items} schema;</li>
 *   <li>a mapped {@code object} with {@code values} advertises {@code additionalProperties};</li>
 *   <li>a nested static {@code value} is always emitted as a string.</li>
 * </ul>
 * <p>Values stay nullable ({@code ["<type>", "null"]}): {@code required} guarantees the key, not
 * a non-null value. Leaf values are extracted as-is, so their declared type is advertised but not
 * coerced.</p>
 *
 * <h2>Steps mode: unshaped, partial output</h2>
 *
 * <p>{@code resolveStepMappings} only sets the keys whose mapping resolved and copies step values
 * through unshaped, so steps-mode schemas declare no {@code required} keys and no
 * {@code items} / {@code additionalProperties}.</p>
 *
 * <p>No {@code additionalProperties: false} is emitted in either mode.</p>
 *
 * <p>This is deliberately not shared with {@code OasExportBuilder#buildOutputSchema}: that
 * builder emits Swagger model objects for OpenAPI 3.x and describes the declared shape, while MCP
 * needs a plain JSON Schema map that a runtime result is guaranteed to conform to.</p>
 */
final class McpToolOutputSchema {

    /** The result path whose output the derived schema describes. */
    enum Source {
        /** Single {@code call} with mapped {@code outputParameters}. */
        CALL,
        /** Orchestrated {@code steps} with {@code mappings}. */
        STEPS
    }

    /**
     * A derived output contract: which result path produces structured output, and its schema.
     *
     * @param source the result path that yields the structured object
     * @param schema the JSON Schema ({@code type: object}) advertised in {@code tools/list}
     */
    record Contract(Source source, Map<String, Object> schema) {
    }

    private McpToolOutputSchema() {
    }

    /**
     * Resolve the output contract of an MCP tool.
     *
     * <p>A tool with a {@code ref} is described from its aggregate flow; an unresolvable ref, or a
     * {@code null} capability, yields no contract.</p>
     *
     * @param tool       the tool spec
     * @param capability the owning capability (used to resolve {@code ref}); may be {@code null}
     * @return the contract, or {@code null} when the tool's output cannot be described as an object
     */
    static Contract resolve(McpServerToolSpec tool, Capability capability) {
        if (tool == null) {
            return null;
        }

        if (tool.getRef() != null) {
            if (capability == null) {
                return null;
            }
            AggregateFlow flow;
            try {
                flow = capability.lookupFlow(tool.getRef());
            } catch (IllegalArgumentException e) {
                return null;
            }
            return resolve(flow.getCall() != null, flow.getSteps() != null
                    && !flow.getSteps().isEmpty(), flow.getOutputParameters(),
                    flow.getMappings());
        }

        return resolve(tool.getCall() != null,
                tool.getSteps() != null && !tool.getSteps().isEmpty(),
                tool.getOutputParameters(), tool.getMappings());
    }

    private static Contract resolve(boolean hasCall, boolean isOrchestrated,
            List<OutputParameterSpec> outputParameters, List<StepOutputMappingSpec> mappings) {
        if (isOrchestrated) {
            Map<String, Object> schema = forSteps(outputParameters, mappings);
            return schema != null ? new Contract(Source.STEPS, schema) : null;
        }
        if (hasCall) {
            Map<String, Object> schema = forCall(outputParameters);
            return schema != null ? new Contract(Source.CALL, schema) : null;
        }
        return null;
    }

    /**
     * Build the output schema for a single-call tool.
     *
     * <p>Only a single root {@code object} with {@code properties} is described: the resolver
     * always emits an object for it carrying every declared key (unresolved properties become
     * {@code null}), so every successful result can carry a conforming {@code structuredContent}
     * and every root property is {@code required}. A {@code values} map root is not described,
     * because an unresolvable map makes the runtime fall back to the raw upstream text.</p>
     *
     * @param outputParameters the tool's mapped output parameters
     * @return the object schema, or {@code null} when the output is not a single mapped object
     */
    static Map<String, Object> forCall(List<OutputParameterSpec> outputParameters) {
        if (outputParameters == null || outputParameters.size() != 1) {
            return null;
        }
        OutputParameterSpec root = outputParameters.get(0);
        if (root == null || root.getValue() != null || root.getValues() != null
                || !"object".equalsIgnoreCase(root.getType())) {
            return null;
        }

        Map<String, Object> properties = propertiesSchema(root.getProperties(), true);
        if (properties.isEmpty()) {
            return null;
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        if (root.getDescription() != null) {
            schema.put("description", root.getDescription());
        }
        return schema;
    }

    /**
     * Build the output schema for an orchestrated tool.
     *
     * @param outputParameters the tool's named (orchestrated) output parameters
     * @param mappings         the step output mappings that assemble the result object
     * @return the object schema, or {@code null} when there are no mappings or no named outputs
     */
    static Map<String, Object> forSteps(List<OutputParameterSpec> outputParameters,
            List<StepOutputMappingSpec> mappings) {
        if (mappings == null || mappings.isEmpty() || outputParameters == null) {
            return null;
        }
        Map<String, Object> properties = propertiesSchema(outputParameters, false);
        if (properties.isEmpty()) {
            return null;
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> propertiesSchema(List<OutputParameterSpec> params,
            boolean shaped) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (params != null) {
            for (OutputParameterSpec param : params) {
                if (param != null && param.getName() != null) {
                    properties.put(param.getName(), propertySchema(param, shaped));
                }
            }
        }
        return properties;
    }

    /**
     * Build the call-mode (shaped) JSON Schema of a nested output parameter.
     *
     * @see #propertySchema(OutputParameterSpec, boolean)
     */
    static Map<String, Object> propertySchema(OutputParameterSpec param) {
        return propertySchema(param, true);
    }

    /**
     * Build the (nullable) JSON Schema of a nested output parameter.
     *
     * <p>When {@code shaped} (call mode) the schema mirrors {@code Resolver#resolveNestedProperty}:
     * static values are strings, assembled objects list every property as {@code required}, mapped
     * arrays describe their {@code items}, and mapped value maps describe their
     * {@code additionalProperties}. When not shaped (steps mode) only the declared type and, for an
     * unmapped object, its properties are described. Unknown or missing types produce an
     * unconstrained schema rather than a guessed one.</p>
     *
     * @param param  the nested output parameter
     * @param shaped whether the runtime shapes this value from its declaration (call mode)
     * @return the JSON Schema of the parameter
     */
    static Map<String, Object> propertySchema(OutputParameterSpec param, boolean shaped) {
        Map<String, Object> schema = new LinkedHashMap<>();

        if (shaped && param.getValue() != null) {
            // Static values are resolved as Mustache templates and always emitted as text.
            schema.put("type", "string");
            putDescription(schema, param);
            return schema;
        }

        String type = jsonType(param.getType());
        if (type != null) {
            schema.put("type", List.of(type, "null"));
        }

        if (shaped) {
            describeShape(param, type, schema);
        } else if ("object".equals(type) && param.getMapping() == null) {
            Map<String, Object> properties = propertiesSchema(param.getProperties(), false);
            if (!properties.isEmpty()) {
                schema.put("properties", properties);
            }
        }

        putDescription(schema, param);
        return schema;
    }

    /**
     * Add the call-mode structure of an object or array parameter to {@code schema}, following
     * the branch order of {@code Resolver#resolveNestedProperty}.
     */
    private static void describeShape(OutputParameterSpec param, String type,
            Map<String, Object> schema) {
        if ("object".equals(type)) {
            if (param.getValues() != null && param.getMapping() != null) {
                schema.put("additionalProperties", leafSchema(param.getValues()));
                return;
            }
            Map<String, Object> properties = propertiesSchema(param.getProperties(), true);
            if (!properties.isEmpty()) {
                schema.put("properties", properties);
                schema.put("required", List.copyOf(properties.keySet()));
            }
        } else if ("array".equals(type) && param.getMapping() != null
                && param.getItems() != null) {
            schema.put("items", itemsSchema(param.getItems()));
        }
    }

    /**
     * Build the schema of a mapped array's elements, mirroring the array branch of
     * {@code Resolver#resolveOutputMappings}: an {@code object} item with {@code properties} is
     * always assembled as an object carrying every declared key; any other item is extracted
     * as-is.
     */
    private static Map<String, Object> itemsSchema(OutputParameterSpec items) {
        if ("object".equalsIgnoreCase(items.getType()) && items.getProperties() != null
                && !items.getProperties().isEmpty()) {
            Map<String, Object> properties = propertiesSchema(items.getProperties(), true);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.copyOf(properties.keySet()));
            putDescription(schema, items);
            return schema;
        }
        return leafSchema(items);
    }

    /**
     * Build the schema of a value that is extracted as-is (an array element or a value-map
     * entry): its declared, nullable type only.
     */
    private static Map<String, Object> leafSchema(OutputParameterSpec param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String type = jsonType(param.getType());
        if (type != null) {
            schema.put("type", List.of(type, "null"));
        }
        putDescription(schema, param);
        return schema;
    }

    private static void putDescription(Map<String, Object> schema, OutputParameterSpec param) {
        if (param.getDescription() != null) {
            schema.put("description", param.getDescription());
        }
    }

    private static String jsonType(String ikanosType) {
        if (ikanosType == null) {
            return null;
        }
        return switch (ikanosType.toLowerCase()) {
            case "string", "number", "boolean", "object", "array" -> ikanosType.toLowerCase();
            default -> null;
        };
    }
}
