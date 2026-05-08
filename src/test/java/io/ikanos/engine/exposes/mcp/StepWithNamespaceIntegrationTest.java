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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.OperationStepCallSpec;

/**
 * Integration test for issue #290: namespace-qualified references in step-level WithInjector.
 *
 * Validates that when an orchestrated MCP tool has a step with
 * {@code with: {voyageId: shipyard-tools.voyageId}}, the namespace-qualified reference is resolved
 * to the caller's argument value — not passed as a literal string.
 *
 * Uses a parameter-capturing executor to observe what parameters are passed to the consumed
 * operation, independent of HTTP transport success/failure.
 */
public class StepWithNamespaceIntegrationTest {

    private Capability capability;
    private McpServerSpec mcpSpec;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp/mcp-step-with-namespace-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Test capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);
        capability = new Capability(spec);

        mcpSpec = (McpServerSpec) spec.getCapability().getExposes().get(0);
    }

    /**
     * Verify that step-level 'with' resolves namespace-qualified references when the
     * OperationStepExecutor is configured with the expose namespace.
     *
     * The step has {@code with: {voyageId: shipyard-tools.voyageId}}, which should resolve
     * to the caller's argument "VOY-2026-042".
     *
     * Before the fix: the namespace was not propagated to the executor, so the literal string
     * "shipyard-tools.voyageId" was used.
     */
    @Test
    public void stepWithShouldResolveNamespaceQualifiedReferences() {
        CapturingStepExecutor executor = new CapturingStepExecutor(capability);
        executor.setExposeNamespace(mcpSpec.getNamespace());

        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setType("call");
        step.setName("get-voyage");
        step.setCall("registry.get-voyage");
        step.setWith(Map.of("voyageId", "shipyard-tools.voyageId"));

        try {
            executor.executeSteps(List.of(step), Map.of("voyageId", "VOY-2026-042"));
        } catch (RuntimeException expected) {
            // HTTP connection failure expected — no server running on port 19999
        }

        assertNotNull(executor.capturedParams,
                "Parameters should have been captured before HTTP call");
        assertEquals("VOY-2026-042", executor.capturedParams.get("voyageId"),
                "Namespace-qualified reference 'shipyard-tools.voyageId' should resolve to "
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
