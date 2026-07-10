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
package io.ikanos.engine.aggregates;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.restlet.Context;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.SemanticsSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolHintsSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Validates aggregate flow references ({@code ref}) in adapter units (MCP tools, REST
 * operations) and derives MCP-specific metadata. Runs at capability load time, before server
 * startup.
 *
 * <p>
 * Validation ensures all refs point to known aggregate flows. For MCP tools, semantics are
 * automatically derived into hints (with field-level override). Adapter-specific metadata
 * (name, description) is inherited when not explicitly set on the adapter unit.
 *
 * <p>
 * Execution fields ({@code call}, {@code steps}, {@code with}, {@code inputParameters},
 * {@code outputParameters}, {@code mappings}) are <b>not</b> copied — at runtime, adapters
 * delegate to {@link AggregateFlow} instances held by the {@link io.ikanos.Capability}.
 */
public class AggregateRefResolver {

    /**
     * Validate all {@code ref} fields across adapter units in the given spec and derive
     * adapter-specific metadata.
     *
     * @param spec The root Naftiko spec to resolve
     * @throws IllegalArgumentException if a ref target is unknown
     */
    public void resolve(IkanosSpec spec) {
        CapabilitySpec capability = spec.getCapability();
        if (capability == null || capability.getAggregates().isEmpty()) {
            return;
        }

        // Build lookup map: "namespace.flowName" → AggregateFlowSpec
        Map<String, AggregateFlowSpec> flowMap = buildFlowMap(capability);

        // Validate refs and derive metadata in all adapter units
        for (ServerSpec serverSpec : capability.getExposes()) {
            if (serverSpec instanceof McpServerSpec mcpSpec) {
                for (McpServerToolSpec tool : mcpSpec.getTools().values()) {
                    if (tool.getRef() != null) {
                        resolveMcpToolRef(tool, flowMap);
                    }
                }
            } else if (serverSpec instanceof RestServerSpec restSpec) {
                for (RestServerResourceSpec resource : restSpec.getResources().values()) {
                    for (RestServerOperationSpec op : resource.getOperations().values()) {
                        if (op.getRef() != null) {
                            resolveRestOperationRef(op, flowMap);
                        }
                    }
                }
            }
        }
    }

    /**
     * Build the lookup map of aggregate flows keyed by "namespace.flowName".
     */
    Map<String, AggregateFlowSpec> buildFlowMap(CapabilitySpec capability) {
        Map<String, AggregateFlowSpec> map = new HashMap<>();

        for (AggregateSpec aggregate : capability.getAggregates().values()) {
            for (AggregateFlowSpec flow : aggregate.getFlows().values()) {
                String key = aggregate.getNamespace() + "." + flow.getName();
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate aggregate flow ref: '" + key + "'");
                }
                map.put(key, flow);
            }
        }

        Context.getCurrentLogger().log(Level.INFO,
                "Built aggregate flow map with {0} entries", map.size());
        return map;
    }

    /**
     * Validate a ref on an MCP tool, inherit adapter-specific metadata, and derive MCP hints
     * from semantics. Execution fields are not copied — they are resolved at runtime via
     * {@link AggregateFlow}.
     */
    void resolveMcpToolRef(McpServerToolSpec tool,
            Map<String, AggregateFlowSpec> flowMap) {
        AggregateFlowSpec flow = lookupFlow(tool.getRef(), flowMap);

        // Inherit name (adapter-specific metadata)
        if (tool.getName() == null || tool.getName().isEmpty()) {
            tool.setName(flow.getName());
        }

        // Inherit description (adapter-specific metadata)
        if (tool.getDescription() == null || tool.getDescription().isEmpty()) {
            tool.setDescription(flow.getDescription());
        }

        // Derive MCP hints from flow semantics, with tool-level override
        if (flow.getSemantics() != null) {
            McpToolHintsSpec derived = deriveHints(flow.getSemantics());
            tool.setHints(mergeHints(derived, tool.getHints()));
        }
    }

    /**
     * Validate a ref on a REST operation and inherit adapter-specific metadata.
     * Execution fields are not copied — they are resolved at runtime via
     * {@link AggregateFlow}.
     */
    void resolveRestOperationRef(RestServerOperationSpec op,
            Map<String, AggregateFlowSpec> flowMap) {
        AggregateFlowSpec flow = lookupFlow(op.getRef(), flowMap);

        // Inherit name
        if (op.getName() == null || op.getName().isEmpty()) {
            op.setName(flow.getName());
        }

        // Inherit description
        if (op.getDescription() == null || op.getDescription().isEmpty()) {
            op.setDescription(flow.getDescription());
        }
    }

    /**
     * Look up a flow by ref key. Fails fast on unknown refs.
     */
    AggregateFlowSpec lookupFlow(String ref,
            Map<String, AggregateFlowSpec> flowMap) {
        AggregateFlowSpec flow = flowMap.get(ref);
        if (flow == null) {
            throw new IllegalArgumentException(
                    "Unknown aggregate flow ref: '" + ref
                            + "'. Available refs: " + flowMap.keySet());
        }
        return flow;
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
