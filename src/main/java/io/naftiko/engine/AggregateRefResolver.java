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
package io.naftiko.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.restlet.Context;
import io.naftiko.spec.AggregateFunctionSpec;
import io.naftiko.spec.AggregateSpec;
import io.naftiko.spec.CapabilitySpec;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.SemanticsSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import io.naftiko.spec.exposes.McpToolHintsSpec;
import io.naftiko.spec.exposes.OperationStepSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.ServerSpec;
import io.naftiko.spec.exposes.StepOutputMappingSpec;

/**
 * Resolves aggregate function references ({@code ref}) in adapter units (MCP tools, REST
 * operations). Runs at capability load time, before server startup.
 * 
 * <p>
 * Resolution merges inherited fields from the referenced function into the adapter unit. Explicit
 * adapter-local fields override inherited ones. For MCP tools, semantics are automatically derived
 * into hints (with field-level override).
 */
public class AggregateRefResolver {

    /**
     * Resolve all {@code ref} fields across adapter units in the given spec. Modifies specs
     * in-place.
     * 
     * @param spec The root Naftiko spec to resolve
     * @throws IllegalArgumentException if a ref target is unknown or a chained ref is detected
     */
    public void resolve(NaftikoSpec spec) {
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getAggregates().isEmpty()) {
            return;
        }

        // Build lookup map: "namespace.functionName" → AggregateFunctionSpec
        Map<String, AggregateFunctionSpec> functionMap = buildFunctionMap(capability);

        // Resolve refs in all adapter units
        for (ServerSpec serverSpec : capability.getExposes()) {
            if (serverSpec instanceof McpServerSpec mcpSpec) {
                for (McpServerToolSpec tool : mcpSpec.getTools()) {
                    if (tool.getRef() != null) {
                        resolveMcpToolRef(tool, functionMap);
                    }
                }
            } else if (serverSpec instanceof RestServerSpec restSpec) {
                for (RestServerResourceSpec resource : restSpec.getResources()) {
                    for (RestServerOperationSpec op : resource.getOperations()) {
                        if (op.getRef() != null) {
                            resolveRestOperationRef(op, functionMap);
                        }
                    }
                }
            }
        }
    }

    /**
     * Build the lookup map of aggregate functions keyed by "namespace.functionName".
     */
    Map<String, AggregateFunctionSpec> buildFunctionMap(CapabilitySpec capability) {
        Map<String, AggregateFunctionSpec> map = new HashMap<>();

        for (AggregateSpec aggregate : capability.getAggregates()) {
            for (AggregateFunctionSpec function : aggregate.getFunctions()) {
                String key = aggregate.getNamespace() + "." + function.getName();
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate aggregate function ref: '" + key + "'");
                }
                map.put(key, function);
            }
        }

        Context.getCurrentLogger().log(Level.INFO,
                "Built aggregate function map with {0} entries", map.size());
        return map;
    }

    /**
     * Resolve a ref on an MCP tool. Merges inherited fields and derives hints from semantics.
     */
    void resolveMcpToolRef(McpServerToolSpec tool,
            Map<String, AggregateFunctionSpec> functionMap) {
        AggregateFunctionSpec function = lookupFunction(tool.getRef(), functionMap);

        // Merge name (function provides default, tool overrides)
        if (tool.getName() == null || tool.getName().isEmpty()) {
            tool.setName(function.getName());
        }

        // Merge description (function provides default, tool overrides)
        if (tool.getDescription() == null || tool.getDescription().isEmpty()) {
            tool.setDescription(function.getDescription());
        }

        // Merge call (function provides default, tool overrides)
        if (tool.getCall() == null && function.getCall() != null) {
            tool.setCall(function.getCall());
        }

        // Merge with (function provides default, tool overrides)
        if (tool.getWith() == null && function.getWith() != null) {
            tool.setWith(function.getWith());
        }

        // Merge steps (function provides default, tool overrides)
        if (tool.getSteps().isEmpty() && !function.getSteps().isEmpty()) {
            for (OperationStepSpec step : function.getSteps()) {
                tool.getSteps().add(step);
            }
        }

        // Merge step output mappings (function provides default, tool overrides)
        if (tool.getMappings().isEmpty() && !function.getMappings().isEmpty()) {
            for (StepOutputMappingSpec mapping : function.getMappings()) {
                tool.getMappings().add(mapping);
            }
        }

        // Merge inputParameters (function provides default, tool overrides)
        if (tool.getInputParameters().isEmpty() && !function.getInputParameters().isEmpty()) {
            for (InputParameterSpec param : function.getInputParameters()) {
                tool.getInputParameters().add(param);
            }
        }

        // Merge outputParameters (function provides default, tool overrides)
        if (tool.getOutputParameters().isEmpty() && !function.getOutputParameters().isEmpty()) {
            for (OutputParameterSpec param : function.getOutputParameters()) {
                tool.getOutputParameters().add(param);
            }
        }

        // Derive MCP hints from function semantics, with tool-level override
        if (function.getSemantics() != null) {
            McpToolHintsSpec derived = deriveHints(function.getSemantics());
            tool.setHints(mergeHints(derived, tool.getHints()));
        }
    }

    /**
     * Resolve a ref on a REST operation. Merges inherited fields.
     */
    void resolveRestOperationRef(RestServerOperationSpec op,
            Map<String, AggregateFunctionSpec> functionMap) {
        AggregateFunctionSpec function = lookupFunction(op.getRef(), functionMap);

        // Merge name
        if (op.getName() == null || op.getName().isEmpty()) {
            op.setName(function.getName());
        }

        // Merge description
        if (op.getDescription() == null || op.getDescription().isEmpty()) {
            op.setDescription(function.getDescription());
        }

        // Merge call
        if (op.getCall() == null && function.getCall() != null) {
            op.setCall(function.getCall());
        }

        // Merge with
        if (op.getWith() == null && function.getWith() != null) {
            op.setWith(function.getWith());
        }

        // Merge steps
        if (op.getSteps().isEmpty() && !function.getSteps().isEmpty()) {
            for (OperationStepSpec step : function.getSteps()) {
                op.getSteps().add(step);
            }
        }

        // Merge step output mappings
        if (op.getMappings().isEmpty() && !function.getMappings().isEmpty()) {
            for (StepOutputMappingSpec mapping : function.getMappings()) {
                op.getMappings().add(mapping);
            }
        }

        // Merge inputParameters
        if (op.getInputParameters().isEmpty() && !function.getInputParameters().isEmpty()) {
            for (InputParameterSpec param : function.getInputParameters()) {
                op.getInputParameters().add(param);
            }
        }

        // Merge outputParameters
        if (op.getOutputParameters().isEmpty() && !function.getOutputParameters().isEmpty()) {
            for (OutputParameterSpec param : function.getOutputParameters()) {
                op.getOutputParameters().add(param);
            }
        }
    }

    /**
     * Look up a function by ref key. Fails fast on unknown or chained refs.
     */
    AggregateFunctionSpec lookupFunction(String ref,
            Map<String, AggregateFunctionSpec> functionMap) {
        AggregateFunctionSpec function = functionMap.get(ref);
        if (function == null) {
            throw new IllegalArgumentException(
                    "Unknown aggregate function ref: '" + ref
                            + "'. Available refs: " + functionMap.keySet());
        }
        return function;
    }

    /**
     * Derive MCP tool hints from transport-neutral semantics.
     * 
     * <p>
     * Mapping:
     * <ul>
     * <li>{@code safe: true} → {@code readOnly: true, destructive: false}</li>
     * <li>{@code safe: false/null} → {@code readOnly: false}</li>
     * <li>{@code idempotent} → copied directly</li>
     * <li>{@code cacheable} → not mapped (no MCP equivalent)</li>
     * <li>{@code openWorld} → not derived (MCP-specific)</li>
     * </ul>
     */
    McpToolHintsSpec deriveHints(SemanticsSpec semantics) {
        McpToolHintsSpec hints = new McpToolHintsSpec();

        if (Boolean.TRUE.equals(semantics.getSafe())) {
            hints.setReadOnly(true);
            hints.setDestructive(false);
        } else if (Boolean.FALSE.equals(semantics.getSafe())) {
            hints.setReadOnly(false);
        }

        if (semantics.getIdempotent() != null) {
            hints.setIdempotent(semantics.getIdempotent());
        }

        // cacheable has no MCP equivalent — not mapped
        // openWorld is MCP-specific — not derived from semantics

        return hints;
    }

    /**
     * Merge derived hints with explicit tool-level overrides. Each non-null explicit field wins over
     * the derived value.
     * 
     * @param derived Hints derived from semantics (never null)
     * @param explicit Explicit tool-level hints (may be null)
     * @return Merged hints
     */
    McpToolHintsSpec mergeHints(McpToolHintsSpec derived, McpToolHintsSpec explicit) {
        if (explicit == null) {
            return derived;
        }

        // Each explicit non-null field overrides the derived value
        if (explicit.getReadOnly() != null) {
            derived.setReadOnly(explicit.getReadOnly());
        }
        if (explicit.getDestructive() != null) {
            derived.setDestructive(explicit.getDestructive());
        }
        if (explicit.getIdempotent() != null) {
            derived.setIdempotent(explicit.getIdempotent());
        }
        if (explicit.getOpenWorld() != null) {
            derived.setOpenWorld(explicit.getOpenWorld());
        }

        return derived;
    }

}
