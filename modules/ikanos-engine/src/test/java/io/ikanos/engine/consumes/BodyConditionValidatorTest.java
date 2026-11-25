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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link BodyConditionValidator} (#1132): a body {@code $when} that names no
 * input of a caller.
 */
class BodyConditionValidatorTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String CONSUMES = """
              consumes:
                - type: "http"
                  namespace: "notion"
                  baseUri: "https://api.notion.com"
                  resources:
                    pages:
                      path: "/v1/pages/{{page_id}}"
                      operations:
                        patch-page:
                          method: "PATCH"
                          inputParameters:
                            page_id:
                              in: "path"
                          body:
                            type: "json"
                            data:
                              notifications: {$when: silent, mode: silent}
                              icon:
                                $first:
                                  - {$when: icon_emoji, type: emoji, emoji: "{{icon_emoji}}"}
                                  - {$when: step.icon, type: external}
                              archived: {$when: page_id, value: true}
            """;

    @Test
    void findWarningsShouldReportWhenNameThatTheToolDoesNotDeclare() throws Exception {
        List<String> warnings = BodyConditionValidator.findWarnings(spec("""
                            silnt:
                              type: boolean
                              description: "Skip notifications"
                            icon_emoji:
                              type: string
                              description: "Emoji"
                """, ""));

        assertEquals(List.of("MCP tool 'patch-page' in 'tools' calls notion.patch-page, whose"
                + " body has '$when: silent', but 'silent' is not one of its inputs: that object"
                + " is never sent from it"), warnings);
    }

    @Test
    void findWarningsShouldAcceptNamesFromInputsWithAndTheConsumedOperation() throws Exception {
        // icon_emoji comes from 'with', page_id from the consumed operation, step.icon is dotted
        List<String> warnings = BodyConditionValidator.findWarnings(spec("""
                            silent:
                              type: boolean
                              description: "Skip notifications"
                """, """
                          with:
                            icon_emoji: "🚀"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findWarningsShouldSkipToolsThatUseSteps() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                %s
                  exposes:
                    - type: "mcp"
                      namespace: "tools"
                      port: 0
                      tools:
                        patch-page:
                          description: "Update a page"
                          steps:
                            patch: {type: call, call: notion.patch-page}
                """.formatted(IKANOS, CONSUMES.stripTrailing()), IkanosSpec.class);

        assertTrue(BodyConditionValidator.findWarnings(spec).isEmpty());
    }

    @Test
    void findWarningsShouldReportAggregateFlowThatDoesNotDeclareTheName() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                %s
                  aggregates:
                    - namespace: "pages"
                      flows:
                        patch:
                          description: "Update a page"
                          inputParameters:
                            icon_emoji:
                              type: string
                              description: "Emoji"
                          call: notion.patch-page
                """.formatted(IKANOS, CONSUMES.stripTrailing()), IkanosSpec.class);

        assertEquals(List.of("aggregate 'pages' flow 'patch' calls notion.patch-page, whose body"
                + " has '$when: silent', but 'silent' is not one of its inputs: that object is"
                + " never sent from it"), BodyConditionValidator.findWarnings(spec));
    }

    @Test
    void findWarningsShouldAcceptNullSpec() {
        assertTrue(BodyConditionValidator.findWarnings(null).isEmpty());
    }

    private static IkanosSpec spec(String toolInputs, String toolWith) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                %s
                  exposes:
                    - type: "mcp"
                      namespace: "tools"
                      port: 0
                      tools:
                        patch-page:
                          description: "Update a page"
                          inputParameters:
                            page_id:
                              type: string
                              required: true
                              description: "Page"
                %s
                          call: notion.patch-page
                %s
                """.formatted(IKANOS, CONSUMES.stripTrailing(), toolInputs.stripTrailing(),
                toolWith.stripTrailing());
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }
}
