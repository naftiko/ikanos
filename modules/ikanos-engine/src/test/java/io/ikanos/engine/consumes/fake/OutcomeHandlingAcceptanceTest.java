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
package io.ikanos.engine.consumes.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Success criteria and early end through a whole capability, on the memo's Page sync case: find
 * the page, create it when it is not found, end early when nothing changed, update it otherwise.
 */
class OutcomeHandlingAcceptanceTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    /** The page sync flow; {@code findStatus} is the status the page lookup answers with. */
    private static String capabilityYaml(int findStatus, String findCriteria) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: fake
                      namespace: notion
                      operations:
                        find-page:
                          outputParameters: []
                        create-page:
                          outputParameters: []
                        update-page:
                          outputParameters: []
                      responses:
                        find-page: '{"id":"p-1","title":"{{title}}","version":3}'
                        create-page: '{"id":"p-2"}'
                        update-page: '{"id":"p-1"}'
                      statuses:
                        find-page: %d
                  aggregates:
                    - namespace: pages
                      flows:
                        sync:
                          description: Sync a page with its source
                          inputParameters:
                            title:
                              type: string
                              description: Page title
                            version:
                              type: number
                              description: Source version
                          steps:
                            - name: find
                              type: call
                              what: notion.find-page
                              with:
                                title: pages.title
                %s
                            - name: create
                              type: call
                              what: notion.create-page
                              onlyIf: "outcome.find == 'not-found'"
                            - name: unchanged
                              type: end
                              onlyIf: "outcome.find == 'success' && find.version == version"
                            - name: update
                              type: call
                              what: notion.update-page
                              onlyIf: "outcome.find == 'success'"
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: pages-mcp
                      description: Page tools
                      tools:
                        sync:
                          description: Sync a page with its source
                          ref: pages.sync
                """.formatted(IKANOS, findStatus, findCriteria.indent(14).stripTrailing());
    }

    private static final String NOT_FOUND_IS_FINE = """
            successCriteria:
              - "outcome.find == 'success' || outcome.find == 'not-found'"
            """;

    private static McpSchema.CallToolResult sync(int findStatus, String criteria, int version)
            throws Exception {
        Capability capability = new Capability(IkanosMappers.lenientYaml().readValue(
                capabilityYaml(findStatus, criteria), IkanosSpec.class));
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
        return mcp.getToolHandler().handleToolCall("sync",
                Map.of("title", "Roadmap", "version", version));
    }

    @BeforeEach
    void setUp() {
        FakeClientAdapter.CALLS.clear();
    }

    @Test
    void aStepAcceptedAsNotFoundShouldLetTheFlowCreateThePage() throws Exception {
        McpSchema.CallToolResult result = sync(404, NOT_FOUND_IS_FINE, 3);

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(2, FakeClientAdapter.CALLS.size(), "find, then create");
    }

    @Test
    void anEndStepShouldEndTheFlowWhenNothingChanged() throws Exception {
        McpSchema.CallToolResult result = sync(200, NOT_FOUND_IS_FINE, 3);

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(1, FakeClientAdapter.CALLS.size(), "find only: the flow ended early");
    }

    @Test
    void theFlowShouldGoPastAnEndStepWhoseGuardDoesNotHold() throws Exception {
        McpSchema.CallToolResult result = sync(200, NOT_FOUND_IS_FINE, 4);

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(2, FakeClientAdapter.CALLS.size(), "find, then update");
    }

    @Test
    void aNotFoundWithoutSuccessCriteriaShouldStillFailTheStep() throws Exception {
        McpSchema.CallToolResult result = sync(404, "", 3);

        assertTrue(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(1, FakeClientAdapter.CALLS.size());
    }

    @Test
    void anOutcomeThatMeetsNoCriterionShouldFailTheStep() throws Exception {
        McpSchema.CallToolResult result = sync(500, NOT_FOUND_IS_FINE, 3);

        assertTrue(Boolean.TRUE.equals(result.isError()), String.valueOf(result));
        assertEquals(1, FakeClientAdapter.CALLS.size());
    }

    @Test
    void aCriterionComparingAnOutcomeWithAnUnknownWordShouldFailTheLoad() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sync(404, """
                        successCriteria:
                          - "outcome.find == 'missing'"
                        """, 3));

        assertTrue(error.getMessage().contains("'missing', which is not one"), error.getMessage());
    }
}
