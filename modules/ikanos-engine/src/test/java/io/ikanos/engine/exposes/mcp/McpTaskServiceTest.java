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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.execution.Durability;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.util.IkanosMappers;

public class McpTaskServiceTest {

    private final McpTaskService service = new McpTaskService(
            Durability.fromSpec(new DurabilitySpec("memory")).runner(), Clock.systemUTC(), null);

    @Test
    void anotherPrincipalShouldNeitherSeeNorCancelATask() {
        String taskId = service.create("mcp:shipyard-ops/clear-for-departure", "agent-7",
                () -> IkanosMappers.json().createObjectNode()).path("taskId").asString();

        assertTrue(service.get(taskId, "agent-7").isPresent());
        assertTrue(service.get(taskId, "agent-8").isEmpty());
        assertFalse(service.cancel(taskId, "agent-8"));
        assertTrue(service.cancel(taskId, "agent-7"));
    }

    @Test
    void anUnknownTaskShouldBeNeitherFoundNorCancelled() {
        assertTrue(service.get("unknown", "agent-7").isEmpty());
        assertTrue(service.get(null, "agent-7").isEmpty());
        assertFalse(service.cancel("unknown", "agent-7"));
    }
}
