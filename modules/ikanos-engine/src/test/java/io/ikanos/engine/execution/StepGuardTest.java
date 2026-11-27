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
package io.ikanos.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.ikanos.spec.util.IkanosMappers;
import org.junit.jupiter.api.Test;

public class StepGuardTest {

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    public void holdsShouldRunAStepWithoutAGuard() {
        assertTrue(StepGuard.holds("s", null, Map.of()));
    }

    @Test
    public void holdsShouldTreatABareNameAsAPresenceTest() {
        Map<String, Object> run = params("customer", Map.of("id", "c-1", "vip", false),
                "empty", "", "zero", 0, "text", "false", "nothing", null);

        assertTrue(StepGuard.holds("s", "customer.id", run));
        assertTrue(StepGuard.holds("s", "empty", run));
        assertTrue(StepGuard.holds("s", "zero", run));
        assertTrue(StepGuard.holds("s", "text", run));
        assertFalse(StepGuard.holds("s", "customer.vip", run));
        assertFalse(StepGuard.holds("s", "nothing", run));
        assertFalse(StepGuard.holds("s", "customer.missing", run));
        assertFalse(StepGuard.holds("s", "absent", run));
    }

    @Test
    public void holdsShouldReadABareNameThroughAJsonStepOutput() {
        Map<String, Object> run = params("get-ship",
                IkanosMappers.json().readTree("{\"status\": \"active\", \"retired\": false}"));

        assertTrue(StepGuard.holds("s", "get-ship.status", run));
        assertFalse(StepGuard.holds("s", "get-ship.retired", run));
    }

    @Test
    public void holdsShouldEvaluateACelExpressionAgainstTheParameters() {
        Map<String, Object> run = params("order", Map.of("status", "overdue", "total", 12),
                "blockers", List.of());

        assertTrue(StepGuard.holds("s", "order.status == 'overdue'", run));
        assertTrue(StepGuard.holds("s", "order.total > 10.5", run));
        assertTrue(StepGuard.holds("s", "size(blockers) == 0", run));
        assertFalse(StepGuard.holds("s", "order.status != 'overdue'", run));
    }

    @Test
    public void holdsShouldBindANameWithADashUnderAnUnderscoreAlias() {
        Map<String, Object> run = params("get-ship",
                IkanosMappers.json().readTree("{\"status\": \"active\"}"));

        assertTrue(StepGuard.holds("s", "get_ship.status == 'active'", run));
    }

    @Test
    public void holdsShouldNotHoldWhenACelExpressionNamesAnAbsentVariable() {
        assertFalse(StepGuard.holds("s", "order.status == 'overdue'", Map.of()));
    }

    @Test
    public void holdsShouldNotTreatComprehensionVariablesAsAbsent() {
        Map<String, Object> run = params("items", List.of(1, 2, 3));

        assertTrue(StepGuard.holds("s", "items.all(x, x > 0)", run));
    }

    @Test
    public void holdsShouldRefuseAGuardThatIsNotABoolean() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("alert", "'yes'", Map.of()));

        assertTrue(error.getMessage().contains("step 'alert'"), error.getMessage());
        assertTrue(error.getMessage().contains("true or false"), error.getMessage());
    }

    @Test
    public void holdsShouldRefuseAGuardThatDoesNotParse() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("alert", "order.status ==", Map.of("order", Map.of())));

        assertTrue(error.getMessage().contains("not a valid CEL expression"), error.getMessage());
    }

    @Test
    public void holdsShouldTreatTrueAndFalseAsCelLiterals() {
        assertTrue(StepGuard.holds("s", "true", Map.of()));
        assertFalse(StepGuard.holds("s", "false", Map.of()));
    }

    @Test
    public void parseErrorShouldReportAnExpressionThatDoesNotParse() {
        assertNull(StepGuard.parseError("order.status == 'x'"));
        assertNull(StepGuard.parseError("customer.id"));
        assertNotNull(StepGuard.parseError("order.status =="));
    }

    @Test
    public void referencedNamesShouldListTheFreeTopLevelNames() {
        assertEquals(Set.of("customer"), StepGuard.referencedNames("customer.id"));
        assertEquals(Set.of("get-ship"), StepGuard.referencedNames("get-ship.status"));
        assertEquals(Set.of("items", "limit"),
                StepGuard.referencedNames("items.all(x, x < limit)"));
    }
}
