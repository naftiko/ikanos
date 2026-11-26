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
package io.ikanos.spec.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ikanos.spec.aggregates.AggregateFlowSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The {@code steps:} list form: how it is read, refused and written back. */
public class StepListFormTest {

    private static AggregateFlowSpec read(String yaml) {
        return IkanosMappers.yaml().readValue(yaml, AggregateFlowSpec.class);
    }

    /** The message of the first cause that is an {@link IllegalArgumentException}. */
    private static String refusal(String yaml) {
        Throwable error = assertThrows(RuntimeException.class, () -> read(yaml));
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                return t.getMessage();
            }
        }
        return error.getMessage();
    }

    @Test
    public void readShouldKeepTheStepsInListOrderUnderTheirNames() {
        AggregateFlowSpec flow = read("""
                steps:
                  - name: zeta
                    type: call
                    what: a.first
                  - name: alpha
                    type: call
                    what: a.second
                """);

        assertEquals(List.of("zeta", "alpha"), List.copyOf(flow.getSteps().keySet()));
        assertEquals("alpha", flow.getSteps().get("alpha").getName());
        assertEquals("a.second",
                ((OperationStepCallSpec) flow.getSteps().get("alpha")).getCall());
    }

    @Test
    public void readShouldRefuseTheMapFormKeyedByStepNameWithTheMigrateHint() {
        String message = refusal("""
                steps:
                  first:
                    type: call
                    what: a.b
                """);

        assertTrue(message.contains("list of steps"), message);
        assertTrue(message.contains("ikanos migrate"), message);
    }

    @Test
    public void readShouldRefuseTwoStepsWithTheSameName() {
        String message = refusal("""
                steps:
                  - name: first
                    type: call
                    what: a.b
                  - name: first
                    type: call
                    what: a.c
                """);

        assertTrue(message.contains("Two steps are named 'first'"), message);
    }

    @Test
    public void readShouldRefuseAStepWithoutAName() {
        String message = refusal("""
                steps:
                  - type: call
                    what: a.b
                """);

        assertTrue(message.contains("Step 1 in 'steps' has no 'name'"), message);
    }

    @Test
    public void readShouldRefuseCallInACallStepWithTheMigrateHint() {
        String message = refusal("""
                steps:
                  - name: first
                    type: call
                    call: a.b
                """);

        assertTrue(message.contains("Step 'first'"), message);
        assertTrue(message.contains("'call:', which is now 'what:'"), message);
        assertTrue(message.contains("ikanos migrate"), message);
    }

    @Test
    public void writeShouldWriteTheStepsAsAListWithTheNameFirst() {
        AggregateFlowSpec flow = read("""
                steps:
                  - name: zeta
                    type: call
                    what: a.first
                  - name: alpha
                    type: lookup
                    index: zeta
                    match: id
                    lookupValue: $.id
                    outputParameters:
                      - name
                """);

        JsonNode steps = IkanosMappers.json().valueToTree(flow).get("steps");

        assertTrue(steps.isArray());
        assertEquals("zeta", steps.get(0).get("name").asString());
        assertEquals("name", steps.get(0).propertyNames().iterator().next());
        assertEquals("a.first", steps.get(0).get("what").asString());
        assertFalse(steps.get(0).has("call"));
        assertEquals("alpha", steps.get(1).get("name").asString());
    }

    @Test
    public void writeThenReadShouldGiveTheSameSteps() {
        AggregateFlowSpec flow = read("""
                steps:
                  - name: first
                    type: call
                    what: a.b
                    with:
                      x: "1"
                """);

        String json = IkanosMappers.json().writeValueAsString(flow);
        AggregateFlowSpec again = IkanosMappers.json().readValue(json, AggregateFlowSpec.class);

        assertEquals(List.of("first"), List.copyOf(again.getSteps().keySet()));
        assertEquals("a.b", ((OperationStepCallSpec) again.getSteps().get("first")).getCall());
    }
}
