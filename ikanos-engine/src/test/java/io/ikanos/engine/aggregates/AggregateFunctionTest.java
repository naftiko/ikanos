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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.aggregates.AggregateFunctionSpec;
import io.ikanos.spec.OutputParameterSpec;

/**
 * Unit tests for {@link AggregateFunction} — namespace-qualified reference resolution
 * in function-level {@code with} blocks.
 */
public class AggregateFunctionTest {

    /**
     * When a mock-mode function has {@code with: {voyage-id: "shipyard.voyage-id"}} and the
     * aggregate namespace is "shipyard", calling execute should resolve the qualified reference
     * to the caller's argument and use it for mock data output.
     *
     * Before the fix: namespace was always {@code null} in mergeWithParameters, so the literal
     * "shipyard.voyage-id" overwrote the caller's "VOY-2026-042" and appeared in the mock output.
     */
    @Test
    void executeShouldResolveNamespaceQualifiedReferencesInFunctionWith() throws Exception {
        AggregateFunctionSpec spec = new AggregateFunctionSpec();
        spec.setName("get-voyage");
        spec.setDescription("Get a voyage manifest.");
        Map<String, Object> withBlock = new HashMap<>();
        withBlock.put("voyage-id", "shipyard.voyage-id");
        spec.setWith(withBlock);

        // Add an output parameter using Mustache to echo the resolved value
        OutputParameterSpec outParam = new OutputParameterSpec();
        outParam.setName("voyage-id");
        outParam.setType("string");
        outParam.setValue("{{voyage-id}}");
        spec.getOutputParameters().add(outParam);

        // No call, no steps -> mock mode
        AggregateFunction fn = new AggregateFunction(spec, null, "shipyard");

        Map<String, Object> callerParams = new HashMap<>();
        callerParams.put("voyage-id", "VOY-2026-042");
        FunctionResult result = fn.execute(callerParams);

        assertTrue(result.isMock(), "Should be mock mode");
        assertNotNull(result.mockOutput, "Mock output should not be null");

        String outputValue = result.mockOutput.get("voyage-id").asText();
        assertEquals("VOY-2026-042", outputValue,
                "Namespace-qualified 'with' should resolve to caller's argument, "
                        + "not the literal 'shipyard.voyage-id'");
    }
}
