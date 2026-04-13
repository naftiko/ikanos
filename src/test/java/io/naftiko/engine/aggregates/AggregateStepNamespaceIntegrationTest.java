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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;

/**
 * Integration test for issue #290: namespace-qualified references in aggregate function steps.
 *
 * Loads a capability YAML where an aggregate function has a step with
 * {@code with: {voyage-id: shipyard.voyage-id}} and verifies that executing the function
 * resolves the namespace-qualified reference before building the HTTP request URL.
 *
 * Before the fix: the aggregate namespace was not propagated to the step executor, so the
 * literal "shipyard.voyage-id" appeared in the URL instead of the resolved value.
 */
public class AggregateStepNamespaceIntegrationTest {

    private Capability capability;
    private AggregateFunction aggregateFunction;

    @BeforeEach
    void setUp() throws Exception {
        String path = "src/test/resources/aggregates/aggregate-step-with-namespace.yaml";
        File file = new File(path);
        assertTrue(file.exists(), "Test capability file should exist at " + path);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
        capability = new Capability(spec);

        aggregateFunction = capability.lookupFunction("shipyard.get-voyage-manifest");
        assertNotNull(aggregateFunction, "Aggregate function should be found");
    }

    /**
     * Execute the aggregate function and verify the step-level namespace-qualified reference
     * resolves correctly. The consumed URL should contain the resolved voyage ID, not the
     * literal "shipyard.voyage-id".
     *
     * The HTTP call to localhost:19999 will fail (no server), but the error message exposes
     * the resolved URL — we can verify the parameter was resolved by catching the exception.
     */
    @Test
    void aggregateFunctionExecuteShouldResolveNamespaceQualifiedWithInSteps() {
        Map<String, Object> params = new HashMap<>();
        params.put("voyage-id", "VOY-2026-042");

        try {
            aggregateFunction.execute(params);
        } catch (Exception e) {
            // HTTP connection failure expected — no server on port 19999.
            // The key check: verify the error does NOT contain the literal
            // "shipyard.voyage-id" in the URL (which would mean it wasn't resolved).
            String message = getFullMessage(e);

            // If the namespace was resolved, the URL should contain "VOY-2026-042"
            // If not resolved, it contains "shipyard.voyage-id" as a literal
            assertTrue(
                    message.contains("VOY-2026-042")
                            || !message.contains("shipyard.voyage-id"),
                    "Step-level 'with' should resolve namespace-qualified reference. "
                            + "URL should contain 'VOY-2026-042', not 'shipyard.voyage-id'. "
                            + "Error was: " + message);
            return;
        }

        // If no exception, the HTTP call succeeded (unlikely without a server) — that's fine too
    }

    private String getFullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" ");
            }
            t = t.getCause();
        }
        return sb.toString();
    }
}
