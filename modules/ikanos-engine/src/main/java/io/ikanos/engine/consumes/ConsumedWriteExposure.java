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
package io.ikanos.engine.consumes;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.Aggregate;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolHintsSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Which consumed write an exposed entry reaches: through its singleton {@code call:}, its steps,
 * the flow it references, and the flows those call, at any depth. A read-only entry must not
 * reach one: an MCP tool advertised as read-only, which a client calls without asking, a REST
 * operation with a safe method ({@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE}), or a
 * flow whose {@code semantics} say {@code safe: true}.
 *
 * <p>Which operation writes is asked of the client adapter that owns the namespace
 * ({@link ClientAdapter#isWriteOperation}): SQL procedures declared {@code readOnly: false}, and
 * the operations of the contract-described adapters ({@code grpc}, {@code graphql},
 * {@code smithy}), whose {@code readOnly} may come from their contract, so {@link #validate}
 * runs once the client adapters exist. The MCP adapter also checks SQL writes from the spec
 * while it is built ({@code SqlWriteExposure}).</p>
 */
public final class ConsumedWriteExposure {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private ConsumedWriteExposure() {
    }

    /**
     * @param capability the capability, for flow lookups
     * @param ref the exposed entry's flow reference, or {@code null}
     * @param call its singleton call, or {@code null}
     * @param steps its steps, or {@code null}
     * @param isWrite whether a {@code namespace.name} call target writes
     * @return the first write target reached, or {@code null}
     */
    public static String writeReached(Capability capability, String ref, ServerCallSpec call,
            Map<String, OperationStepSpec> steps, Predicate<String> isWrite) {
        Set<AggregateFlow> seen = new HashSet<>();
        if (ref != null) {
            return inFlow(capability, findFlow(capability, ref), seen, isWrite);
        }
        String reached = inCall(capability, call, seen, isWrite);
        return reached != null ? reached : inSteps(capability, steps, seen, isWrite);
    }

    /**
     * Refuses a read-only MCP tool, a REST operation with a safe method, or a flow with
     * {@code semantics.safe: true} that reaches a consumed write.
     *
     * @param spec the capability section
     * @param capability the capability
     * @param clients the client adapters, built
     * @throws IllegalArgumentException naming the entry and the write
     */
    public static void validate(CapabilitySpec spec, Capability capability,
            List<ClientAdapter> clients) {
        if (spec == null) {
            return;
        }
        Predicate<String> isWrite = target -> consumedWrite(clients, target);
        validateFlows(capability, isWrite);
        if (spec.getExposes() == null) {
            return;
        }
        for (ServerSpec server : spec.getExposes()) {
            if (server instanceof McpServerSpec mcp) {
                validateTools(capability, mcp, isWrite);
            } else if (server instanceof RestServerSpec rest) {
                validateOperations(capability, rest, isWrite);
            }
        }
    }

    private static void validateTools(Capability capability, McpServerSpec mcp,
            Predicate<String> isWrite) {
        for (McpServerToolSpec tool : mcp.getTools().values()) {
            McpToolHintsSpec hints = mcp.resolveToolHints(tool);
            if (hints == null || !Boolean.TRUE.equals(hints.getReadOnly())) {
                continue;
            }
            String write = writeReached(capability, tool.getRef(), tool.getCall(),
                    tool.getSteps(), isWrite);
            if (write != null) {
                throw new IllegalArgumentException("MCP tool '" + tool.getName()
                        + "' of namespace '" + mcp.getNamespace() + "' resolves"
                        + " hints.readOnly: true, declared or inherited from the exposes"
                        + " block, but reaches the consumed operation '" + write
                        + "', which writes; declare hints.readOnly: false on the tool");
            }
        }
    }

    private static void validateOperations(Capability capability, RestServerSpec rest,
            Predicate<String> isWrite) {
        for (RestServerResourceSpec resource : rest.getResources().values()) {
            for (RestServerOperationSpec operation : resource.getOperations().values()) {
                String method = operation.getMethod() != null
                        ? operation.getMethod().toUpperCase(Locale.ROOT) : null;
                if (method == null || !SAFE_METHODS.contains(method)) {
                    continue;
                }
                String write = writeReached(capability, operation.getRef(), operation.getCall(),
                        operation.getSteps(), isWrite);
                if (write != null) {
                    throw new IllegalArgumentException("REST operation '" + operation.getName()
                            + "' (" + method + " " + resource.getPath() + ") of namespace '"
                            + rest.getNamespace() + "' uses a safe method but reaches the"
                            + " consumed operation '" + write + "', which writes; use a method"
                            + " that is not safe, such as POST");
                }
            }
        }
    }

    private static void validateFlows(Capability capability, Predicate<String> isWrite) {
        List<Aggregate> aggregates = capability != null ? capability.getAggregates() : null;
        if (aggregates == null) {
            return;
        }
        for (Aggregate aggregate : aggregates) {
            for (AggregateFlow flow : aggregate.getFlows()) {
                if (flow.getSemantics() == null
                        || !Boolean.TRUE.equals(flow.getSemantics().getSafe())) {
                    continue;
                }
                String write = inFlow(capability, flow, new HashSet<>(), isWrite);
                if (write != null) {
                    throw new IllegalArgumentException("Aggregate flow '" + flow.getRef()
                            + "' declares semantics.safe: true but reaches the consumed"
                            + " operation '" + write + "', which writes; declare"
                            + " semantics.safe: false on the flow");
                }
            }
        }
    }

    /**
     * @return whether the client adapter that owns the target's namespace says its operation
     *         writes, whatever the adapter's type
     */
    static boolean consumedWrite(List<ClientAdapter> clients, String target) {
        int dot = target.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String namespace = target.substring(0, dot);
        String name = target.substring(dot + 1);
        for (ClientAdapter client : clients) {
            if (namespace.equals(client.getNamespace())) {
                return client.isWriteOperation(name);
            }
        }
        return false;
    }

    private static String inFlow(Capability capability, AggregateFlow flow,
            Set<AggregateFlow> seen, Predicate<String> isWrite) {
        if (flow == null || !seen.add(flow)) {
            return null;
        }
        String reached = inCall(capability, flow.getCall(), seen, isWrite);
        return reached != null ? reached : inSteps(capability, flow.getSteps(), seen, isWrite);
    }

    private static String inCall(Capability capability, ServerCallSpec call,
            Set<AggregateFlow> seen, Predicate<String> isWrite) {
        return call != null && call.getOperation() != null
                ? inTarget(capability, call.getOperation(), seen, isWrite) : null;
    }

    private static String inTarget(Capability capability, String target,
            Set<AggregateFlow> seen, Predicate<String> isWrite) {
        if (isWrite.test(target)) {
            return target;
        }
        return inFlow(capability, findFlow(capability, target), seen, isWrite);
    }

    private static String inSteps(Capability capability, Map<String, OperationStepSpec> steps,
            Set<AggregateFlow> seen, Predicate<String> isWrite) {
        if (steps == null) {
            return null;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepCallSpec call && call.getCall() != null) {
                String reached = inTarget(capability, call.getCall(), seen, isWrite);
                if (reached != null) {
                    return reached;
                }
            }
        }
        return null;
    }

    private static AggregateFlow findFlow(Capability capability, String ref) {
        try {
            return capability.lookupFlow(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
