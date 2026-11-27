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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code display} was renamed to {@code title} with no alias (#1164): a spec element that still
 * sets it fails to load, with a message that names {@code title}, even through the lenient mapper
 * that otherwise ignores unknown keys.
 */
class RemovedDisplayKeyTest {

    private static final String CAPABILITY = """
            ikanos: "1.0.0"
            info:
              %s: "Fleet"
              description: "Removed display key"
            capability:
              exposes:
                - type: mcp
                  namespace: fleet
                  port: 3001
                  tools:
                    list-ships:
                      %s: "List Ships"
                      description: "List ships."
                      call: registry.list-ships
                      with:
                        display: "compact"
            """;

    private static IkanosSpec read(ObjectMapper mapper, String infoKey, String toolKey) {
        return mapper.readValue(CAPABILITY.formatted(infoKey, toolKey), IkanosSpec.class);
    }

    @Test
    void lenientMapperShouldRejectDisplayOnInfo() {
        DatabindException e = assertThrows(DatabindException.class,
                () -> read(IkanosMappers.lenientYaml(), "display", "title"));

        assertTrue(e.getMessage().contains("`display` is no longer supported: it was renamed to "
                + "`title`"), e.getMessage());
        assertTrue(e.getMessage().contains("\"info\""), e.getMessage());
    }

    @Test
    void lenientMapperShouldRejectDisplayOnTool() {
        DatabindException e = assertThrows(DatabindException.class,
                () -> read(IkanosMappers.lenientYaml(), "title", "display"));

        assertTrue(e.getMessage().contains("Rename the `display` key to `title`"), e.getMessage());
        assertTrue(e.getMessage().contains("\"list-ships\"") || e.getMessage().contains("tools"),
                e.getMessage());
    }

    @Test
    void strictMapperShouldRejectDisplayWithTheSameMessage() {
        DatabindException e = assertThrows(DatabindException.class,
                () -> read(IkanosMappers.yaml(), "display", "title"));

        assertTrue(e.getMessage().contains("renamed to `title`"), e.getMessage());
    }

    @Test
    void titleShouldLoadAndADisplayKeyInUserDataShouldStayUntouched() {
        IkanosSpec spec = read(IkanosMappers.lenientYaml(), "title", "title");

        assertEquals("Fleet", spec.getInfo().getTitle());
        McpServerSpec mcp = (McpServerSpec) spec.getCapability().getExposes().get(0);
        assertEquals("List Ships", mcp.getTools().get("list-ships").getTitle());
        assertEquals("compact", mcp.getTools().get("list-ships").getWith().get("display"));
    }

}
