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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OperationStepExecutor#mergeWithParameters(Map, Map, String)}.
 */
public class OperationStepExecutorTest {

    @Test
    public void mergeWithShouldResolveMustacheTemplate() {
        Map<String, Object> target = new HashMap<>();
        target.put("imo", "IMO-9321483");

        Map<String, Object> with = new HashMap<>();
        with.put("imo_number", "{{imo}}");

        OperationStepExecutor.mergeWithParameters(with, target, null);

        assertEquals("IMO-9321483", target.get("imo_number"),
                "Mustache template {{imo}} should be resolved to the actual value");
    }

    @Test
    public void mergeWithShouldResolveNamespaceQualifiedReference() {
        Map<String, Object> target = new HashMap<>();
        target.put("shipImo", "IMO-9321483");

        Map<String, Object> with = new HashMap<>();
        with.put("shipImo", "shipyard-api.shipImo");

        OperationStepExecutor.mergeWithParameters(with, target, "shipyard-api");

        assertEquals("IMO-9321483", target.get("shipImo"),
                "Namespace-qualified reference shipyard-api.shipImo should resolve to actual value");
    }

    @Test
    public void mergeWithShouldReturnNullForUnresolvedNamespaceRef() {
        Map<String, Object> target = new HashMap<>();

        Map<String, Object> with = new HashMap<>();
        with.put("shipImo", "shipyard-api.shipImo");

        OperationStepExecutor.mergeWithParameters(with, target, "shipyard-api");

        assertNull(target.get("shipImo"),
                "Unresolved namespace-qualified reference should not add a null entry");
    }

    @Test
    public void mergeWithShouldPassThroughNonStringValue() {
        Map<String, Object> target = new HashMap<>();

        Map<String, Object> with = new HashMap<>();
        with.put("pageSize", 100);

        OperationStepExecutor.mergeWithParameters(with, target, null);

        assertEquals(100, target.get("pageSize"),
                "Non-string values should be passed through as-is");
    }

    @Test
    public void mergeWithNullShouldBeNoOp() {
        Map<String, Object> target = new HashMap<>();
        target.put("existing", "value");

        OperationStepExecutor.mergeWithParameters(null, target, null);

        assertEquals(1, target.size(), "Null 'with' map should not modify the target");
    }

    @Test
    public void mergeWithShouldTreatNamespaceRefAsLiteralWhenNamespaceIsNull() {
        Map<String, Object> target = new HashMap<>();
        target.put("voyageId", "VOY-2026-042");

        Map<String, Object> with = new HashMap<>();
        with.put("voyageId", "shipyard-tools.voyageId");

        OperationStepExecutor.mergeWithParameters(with, target, null);

        assertEquals("shipyard-tools.voyageId", target.get("voyageId"),
                "Without namespace, qualified reference should be treated as Mustache literal");
    }
}
