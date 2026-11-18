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
package io.ikanos.engine.consumes.sql;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.consumes.sql.SqlProcedureSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Which SQL write procedure ({@code readOnly: false}) an exposed entry reaches: through its
 * singleton {@code call:}, its steps, the flow it references, and the flows those call, at any
 * depth. The MCP adapter refuses, at load, a tool advertised as read-only that reaches one, as
 * the {@code ikanos-sql-write-exposure} rule does.
 */
public final class SqlWriteExposure {

    private SqlWriteExposure() {
    }

    /**
     * @param ref the flow the entry references with {@code ref:}, or {@code null}
     * @param call the entry's singleton {@code call:}, used when it references no flow; may be
     *        {@code null}
     * @param steps the entry's own steps, used when it references no flow; may be {@code null}
     * @return the first write procedure reached, as {@code namespace.procedure}, or {@code null}
     */
    public static String writeReached(Capability capability, String ref, ServerCallSpec call,
            Map<String, OperationStepSpec> steps) {
        Set<AggregateFlow> seen = new HashSet<>();
        if (ref != null) {
            return inFlow(capability, findFlow(capability, ref), seen);
        }
        String reached = inCall(capability, call, seen);
        return reached != null ? reached : inSteps(capability, steps, seen);
    }

    private static String inFlow(Capability capability, AggregateFlow flow,
            Set<AggregateFlow> seen) {
        if (flow == null || !seen.add(flow)) {
            return null;
        }
        String reached = inCall(capability, flow.getCall(), seen);
        return reached != null ? reached : inSteps(capability, flow.getSteps(), seen);
    }

    private static String inCall(Capability capability, ServerCallSpec call,
            Set<AggregateFlow> seen) {
        return call != null && call.getOperation() != null
                ? inTarget(capability, call.getOperation(), seen) : null;
    }

    private static String inTarget(Capability capability, String target,
            Set<AggregateFlow> seen) {
        if (isWrite(capability, target)) {
            return target;
        }
        return inFlow(capability, findFlow(capability, target), seen);
    }

    private static String inSteps(Capability capability, Map<String, OperationStepSpec> steps,
            Set<AggregateFlow> seen) {
        if (steps == null) {
            return null;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepCallSpec call && call.getCall() != null) {
                String reached = inTarget(capability, call.getCall(), seen);
                if (reached != null) {
                    return reached;
                }
            }
        }
        return null;
    }

    /** Whether {@code target} names a consumed SQL procedure declared {@code readOnly: false}. */
    static boolean isWrite(Capability capability, String target) {
        int dot = target.indexOf('.');
        if (dot <= 0 || capability.getSpec() == null || capability.getSpec().getCapability() == null
                || capability.getSpec().getCapability().getConsumes() == null) {
            return false;
        }
        String namespace = target.substring(0, dot);
        String name = target.substring(dot + 1);
        for (ClientSpec client : capability.getSpec().getCapability().getConsumes()) {
            if (client instanceof SqlClientSpec sql && namespace.equals(sql.getNamespace())) {
                SqlProcedureSpec procedure = sql.getProcedures().get(name);
                return procedure != null && Boolean.FALSE.equals(procedure.getReadOnly());
            }
        }
        return false;
    }

    private static AggregateFlow findFlow(Capability capability, String ref) {
        try {
            return capability.lookupFlow(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
