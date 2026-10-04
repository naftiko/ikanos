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
 * <p>Every non-root type is nullable ({@code ["<type>", "null"]}) and no property is
 * {@code required}, because the resolver fills unresolved mappings with {@code null} (call mode)
 * or omits them (steps mode). No {@code additionalProperties: false} is emitted, and the shape of
 * a value extracted by a {@code mapping} (e.g. array {@code items}) is not described, because
 * such values are passed through from the upstream response as-is.</p>
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
     * always emits an object for it (unresolved properties become {@code null}), so every
     * successful result can carry a conforming {@code structuredContent}. A {@code values} map
     * root is not described, because an unresolvable map makes the runtime fall back to the raw
     * upstream text.</p>
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

        Map<String, Object> properties = propertiesSchema(root.getProperties());
        if (properties.isEmpty()) {
            return null;
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
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
        Map<String, Object> properties = propertiesSchema(outputParameters);
        if (properties.isEmpty()) {
            return null;
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> propertiesSchema(List<OutputParameterSpec> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (params != null) {
            for (OutputParameterSpec param : params) {
                if (param != null && param.getName() != null) {
                    properties.put(param.getName(), propertySchema(param));
                }
            }
        }
        return properties;
    }

    /**
     * Build the (nullable) JSON Schema of a nested output parameter, mirroring
     * {@code Resolver#resolveOutputMappings}: a nested {@code object} without a {@code mapping}
     * is assembled from its {@code properties}; any other nested parameter (including arrays and
     * mapped objects) is extracted as-is, so only its type is described. Unknown or missing types
     * produce an unconstrained schema rather than a guessed one.
     */
    static Map<String, Object> propertySchema(OutputParameterSpec param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String type = jsonType(param.getType());

        if (type != null) {
            schema.put("type", List.of(type, "null"));
        }

        if ("object".equals(type) && param.getMapping() == null) {
            Map<String, Object> properties = propertiesSchema(param.getProperties());
            if (!properties.isEmpty()) {
                schema.put("properties", properties);
            }
        }

        if (param.getDescription() != null) {
            schema.put("description", param.getDescription());
        }
        return schema;
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
