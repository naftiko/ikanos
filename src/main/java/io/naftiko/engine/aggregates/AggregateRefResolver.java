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
package io.naftiko.engine.aggregates;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.restlet.Context;
import io.naftiko.spec.aggregates.AggregateFunctionSpec;
import io.naftiko.spec.aggregates.AggregateSpec;
import io.naftiko.spec.CapabilitySpec;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.aggregates.SemanticsSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import io.naftiko.spec.exposes.McpToolHintsSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Validates aggregate function references ({@code ref}) in adapter units (MCP tools, REST
 * operations) and derives MCP-specific metadata. Runs at capability load time, before server
 * startup.
 *
 * <p>
 * Validation ensures all refs point to known aggregate functions. For MCP tools, semantics are
 * automatically derived into hints (with field-level override). Adapter-specific metadata
 * (name, description) is inherited when not explicitly set on the adapter unit.
 *
 * <p>
 * Execution fields ({@code call}, {@code steps}, {@code with}, {@code inputParameters},
 * {@code outputParameters}, {@code mappings}) are <b>not</b> copied — at runtime, adapters
 * delegate to {@link AggregateFunction} instances held by the {@link io.naftiko.Capability}.
 */
public class AggregateRefResolver {

    /**
     * Validate all {@code ref} fields across adapter units in the given spec and derive
     * adapter-specific metadata.
     * 
     * @param spec The root Naftiko spec to resolve
     * @throws IllegalArgumentException if a ref target is unknown
     */
    public void resolve(NaftikoSpec spec) {
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getAggregates().isEmpty()) {
            return;
        }

        // Build lookup map: "namespace.functionName" → AggregateFunctionSpec
        Map<String, AggregateFunctionSpec> functionMap = buildFunctionMap(capability);

        // Validate refs and derive metadata in all adapter units
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
     * Validate a ref on an MCP tool, inherit adapter-specific metadata, and derive MCP hints
     * from semantics. Execution fields are not copied — they are resolved at runtime via
     * {@link AggregateFunction}.
     */
    void resolveMcpToolRef(McpServerToolSpec tool,
            Map<String, AggregateFunctionSpec> functionMap) {
        AggregateFunctionSpec function = lookupFunction(tool.getRef(), functionMap);

        // Inherit name (adapter-specific metadata)
        if (tool.getName() == null || tool.getName().isEmpty()) {
            tool.setName(function.getName());
        }

        // Inherit description (adapter-specific metadata)
        if (tool.getDescription() == null || tool.getDescription().isEmpty()) {
            tool.setDescription(function.getDescription());
        }

        // Derive MCP hints from function semantics, with tool-level override
        if (function.getSemantics() != null) {
            McpToolHintsSpec derived = deriveHints(function.getSemantics());
            tool.setHints(mergeHints(derived, tool.getHints()));
        }
    }

    /**
     * Validate a ref on a REST operation and inherit adapter-specific metadata.
     * Execution fields are not copied — they are resolved at runtime via
     * {@link AggregateFunction}.
     */
    void resolveRestOperationRef(RestServerOperationSpec op,
            Map<String, AggregateFunctionSpec> functionMap) {
        AggregateFunctionSpec function = lookupFunction(op.getRef(), functionMap);

        // Inherit name
        if (op.getName() == null || op.getName().isEmpty()) {
            op.setName(function.getName());
        }

        // Inherit description
        if (op.getDescription() == null || op.getDescription().isEmpty()) {
            op.setDescription(function.getDescription());
        }
    }

    /**
     * Look up a function by ref key. Fails fast on unknown refs.
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
