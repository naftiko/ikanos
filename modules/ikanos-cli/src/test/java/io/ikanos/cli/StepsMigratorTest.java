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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ikanos.spec.util.IkanosMappers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

public class StepsMigratorTest {

    @Test
    public void migrateYamlShouldTurnAStepMapIntoANamedListInTheSameOrder() {
        String before = """
                flows:
                  get-ship:
                    steps:
                      get-ship:
                        type: call
                        call: registry.get-ship
                        with:
                          imo: "{{imo}}"
                      resolve-crew:
                        type: lookup
                        index: get-ship
                    mappings:
                    - target: name
                """;
        String after = """
                flows:
                  get-ship:
                    steps:
                      - name: get-ship
                        type: call
                        what: registry.get-ship
                        with:
                          imo: "{{imo}}"
                      - name: resolve-crew
                        type: lookup
                        index: get-ship
                    mappings:
                    - target: name
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldKeepCommentsAndBlankLines() {
        String before = """
                steps:
                  # read first
                  first: # the ship
                    type: call
                    # upstream
                    call: registry.get-ship

                  second:
                    type: call
                    call: registry.list-crew
                """;
        String after = """
                steps:
                  # read first
                  - name: first # the ship
                    type: call
                    # upstream
                    what: registry.get-ship

                  - name: second
                    type: call
                    what: registry.list-crew
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldReindentABodyIndentedDeeperThanTwoSpaces() {
        String before = """
                steps:
                    first:
                        type: call
                        call: a.b
                        with:
                            x: "1"
                """;
        String after = """
                steps:
                    - name: first
                      type: call
                      what: a.b
                      with:
                          x: "1"
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldRewriteAnInlineStep() {
        String before = """
                steps:
                  first: { type: call, call: a.b }
                  second: {type: lookup, index: first}
                """;
        String after = """
                steps:
                  - {name: first, type: call, what: a.b }
                  - {name: second, type: lookup, index: first}
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldRenameCallInAStepListThatAlreadyHasNames() {
        String before = """
                steps:
                  - name: first
                    type: call
                    call: a.b
                  - {name: second, type: call, call: c.d}
                """;
        String after = """
                steps:
                  - name: first
                    type: call
                    what: a.b
                  - {name: second, type: call, what: c.d}
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldLeaveAMigratedDocumentUnchanged() {
        String migrated = """
                steps:
                  - name: first
                    type: call
                    what: a.b
                    with:
                      call: kept
                """;

        assertEquals(migrated, StepsMigrator.migrateYaml(migrated));
    }

    @Test
    public void migrateYamlShouldLeaveAStepsKeyWhoseEntriesDeclareNoType() {
        String other = """
                with:
                  steps:
                    first:
                      call: kept
                """;

        assertEquals(other, StepsMigrator.migrateYaml(other));
    }

    @Test
    public void migrateYamlShouldRewriteStepsNestedUnderAKeyNamedSteps() {
        String before = """
                resources:
                  steps:
                    path: /steps
                    operations:
                      steps:
                        method: POST
                        steps:
                          declare: { type: call, call: customs.declare }
                """;
        String after = """
                resources:
                  steps:
                    path: /steps
                    operations:
                      steps:
                        method: POST
                        steps:
                          - {name: declare, type: call, what: customs.declare }
                """;

        assertEquals(after, StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateYamlShouldKeepTheUnitCallOfAToolWithoutSteps() {
        String tool = """
                tools:
                  get-ship:
                    call: registry.get-ship
                """;

        assertEquals(tool, StepsMigrator.migrateYaml(tool));
    }

    @Test
    public void migrateYamlShouldKeepWindowsLineEndings() {
        String before = "steps:\r\n  first:\r\n    type: call\r\n    call: a.b\r\n";

        assertEquals("steps:\r\n  - name: first\r\n    type: call\r\n    what: a.b\r\n",
                StepsMigrator.migrateYaml(before));
    }

    @Test
    public void migrateJsonShouldTurnAStepMapIntoANamedListAndRenameCall() {
        String before = """
                {"flows": {"f": {"steps": {
                  "first": {"type": "call", "call": "a.b", "with": {"x": "1"}},
                  "second": {"type": "lookup", "index": "first"}}}}}
                """;

        JsonNode steps = IkanosMappers.json().readTree(StepsMigrator.migrateJson(before))
                .at("/flows/f/steps");

        assertTrue(steps.isArray());
        assertEquals("first", steps.get(0).get("name").asString());
        assertEquals("a.b", steps.get(0).get("what").asString());
        assertFalse(steps.get(0).has("call"));
        assertEquals("second", steps.get(1).get("name").asString());
    }

    @Test
    public void migrateJsonShouldReturnTheSameTextWhenNothingChanges() {
        String migrated = "{\"steps\": [{\"name\": \"first\", \"type\": \"call\", \"what\": \"a.b\"}]}";

        assertEquals(migrated, StepsMigrator.migrateJson(migrated));
    }
}
