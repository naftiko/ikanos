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

import java.util.Map;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedWriteExposure;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.consumes.sql.SqlProcedureSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
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
        return ConsumedWriteExposure.writeReached(capability, ref, call, steps,
                target -> isWrite(capability, target));
    }

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
}
