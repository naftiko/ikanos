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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.contract.ContractClientAdapter;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

class ConsumedWriteExposureTest {

    @Test
    void contractWriteShouldAskTheAdapterOfTheNamespace() {
        ContractClientAdapter orders = mock(ContractClientAdapter.class);
        when(orders.getNamespace()).thenReturn("orders");
        when(orders.isWriteOperation("cancel-order")).thenReturn(true);

        assertTrue(ConsumedWriteExposure.contractWrite(List.of(orders), "orders.cancel-order"));
        assertFalse(ConsumedWriteExposure.contractWrite(List.of(orders), "orders.get-order"));
        assertFalse(ConsumedWriteExposure.contractWrite(List.of(orders), "other.cancel-order"));
        assertFalse(ConsumedWriteExposure.contractWrite(List.of(orders), "no-dot"));
    }

    @Test
    void contractWriteShouldIgnoreOtherAdapters() {
        ClientAdapter sql = mock(ClientAdapter.class);
        when(sql.getNamespace()).thenReturn("erp");
        when(sql.isWriteOperation("save")).thenReturn(true);

        assertFalse(ConsumedWriteExposure.contractWrite(List.of(sql), "erp.save"),
                "SQL writes are checked by the MCP adapter from the spec");
    }

    @Test
    void writeReachedShouldFindAWriteInASingletonCall() {
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("orders.cancel-order");

        assertEquals("orders.cancel-order", ConsumedWriteExposure.writeReached(
                mock(Capability.class), null, call, null, "orders.cancel-order"::equals));
    }

    @Test
    void writeReachedShouldFindAWriteInASteps() {
        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setCall("orders.cancel-order");
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("cancel", step);

        assertEquals("orders.cancel-order", ConsumedWriteExposure.writeReached(
                mock(Capability.class), null, null, steps, "orders.cancel-order"::equals));
    }

    @Test
    void writeReachedShouldReturnNullWithoutWrite() {
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("orders.get-order");
        Capability capability = mock(Capability.class);
        when(capability.lookupFlow("orders.get-order"))
                .thenThrow(new IllegalArgumentException("no flow"));

        assertNull(ConsumedWriteExposure.writeReached(capability, null, call, null,
                target -> false));
    }
}
