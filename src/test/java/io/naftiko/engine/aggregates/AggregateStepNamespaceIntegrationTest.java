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

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.spec.AggregateSpec;
import io.naftiko.spec.NaftikoSpec;

/**
 * Integration test for issue #290: namespace-qualified references in aggregate function steps.
 *
 * Loads a capability YAML where an aggregate function has a step with
 * {@code with: {voyage-id: shipyard.voyage-id}} and verifies that executing the function
 * resolves the namespace-qualified reference before building the HTTP request URL.
 *
 * Uses a parameter-capturing executor to observe what parameters are passed to the consumed
 * operation, independent of HTTP transport success/failure.
 *
 * Before the fix: the aggregate namespace was not propagated to the step executor, so the
 * literal "shipyard.voyage-id" appeared in the URL instead of the resolved value.
 */
public class AggregateStepNamespaceIntegrationTest {

    private Capability capability;
    private NaftikoSpec spec;

    @BeforeEach
    void setUp() throws Exception {
        String path = "src/test/resources/aggregates/aggregate-step-with-namespace.yaml";
        File file = new File(path);
        assertTrue(file.exists(), "Test capability file should exist at " + path);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        spec = mapper.readValue(file, NaftikoSpec.class);
        capability = new Capability(spec);
    }

    /**
     * Execute the aggregate function and verify the step-level namespace-qualified reference
     * resolves correctly. A CapturingStepExecutor captures the resolved parameters before the
     * HTTP call, so the assertion does not depend on error message contents.
     */
    @Test
    void aggregateFunctionExecuteShouldResolveNamespaceQualifiedWithInSteps() {
        CapturingStepExecutor executor = new CapturingStepExecutor(capability);

        AggregateSpec aggregateSpec = spec.getCapability().getAggregates().get(0);
        Aggregate aggregate = new Aggregate(aggregateSpec, executor);
        AggregateFunction fn = aggregate.findFunction("get-voyage-manifest");
        assertNotNull(fn, "Aggregate function should be found");

        Map<String, Object> params = new HashMap<>();
        params.put("voyage-id", "VOY-2026-042");

        try {
            fn.execute(params);
        } catch (Exception expected) {
            // HTTP connection failure expected — no server on port 19999
        }

        assertNotNull(executor.capturedParams,
                "Parameters should have been captured before HTTP call");
        assertEquals("VOY-2026-042", executor.capturedParams.get("voyage-id"),
                "Namespace-qualified reference 'shipyard.voyage-id' should resolve to "
                        + "the caller's argument, not be passed as literal");
    }

    /**
     * Test-only subclass that captures parameters at the HTTP request construction point.
     */
    static class CapturingStepExecutor extends OperationStepExecutor {
        Map<String, Object> capturedParams;

        CapturingStepExecutor(Capability capability) {
            super(capability);
        }

        @Override
        public HandlingContext findClientRequestFor(String clientNamespace, String clientOpName,
                Map<String, Object> parameters) {
            capturedParams = new HashMap<>(parameters);
            return super.findClientRequestFor(clientNamespace, clientOpName, parameters);
        }
    }
}
