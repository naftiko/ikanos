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
import io.ikanos.engine.util.CallerRoot;
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
    public void holdsShouldNotHoldWhenACelExpressionReadsAFieldTheValueDoesNotHave() {
        Map<String, Object> run = params("order", Map.of("status", "paid"));

        assertFalse(StepGuard.holds("s", "order.missing == 'x'", run));
    }

    @Test
    public void holdsShouldRefuseAGuardThatCallsAFunctionThatDoesNotExist() {
        Map<String, Object> run = params("items", List.of(1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("s", "sizee(items) > 0", run));

        assertTrue(error.getMessage().contains("not a valid CEL expression"), error.getMessage());
    }

    @Test
    public void parseErrorShouldReportAMethodThatDoesNotExist() {
        assertNotNull(StepGuard.parseError("name.startsWit('a')"));
    }

    @Test
    public void holdsShouldRefuseAGuardReadingTheCallerClaimsWhenTheRunHasNone() {
        IllegalArgumentException bare = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("s", "auth.claims.sub", Map.of()));
        IllegalArgumentException cel = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("s", "auth.claims.sub == 'u-1'", Map.of()));

        assertTrue(bare.getMessage().contains("caller's claims"), bare.getMessage());
        assertTrue(cel.getMessage().contains("caller's claims"), cel.getMessage());
    }

    @Test
    public void holdsShouldReadTheCallerClaimsOfTheRun() {
        Map<String, Object> run = params("auth",
                new CallerRoot(Map.of("claims", Map.of("sub", "u-1", "cognito:groups", "ops"))));

        assertTrue(StepGuard.holds("s", "auth.claims.sub == 'u-1'", run));
        assertTrue(StepGuard.holds("s", "auth.claims.cognito:groups", run));
        assertFalse(StepGuard.holds("s", "auth.claims.email", run));
    }

    @Test
    public void isBareNameShouldAcceptTheNamesOfABodyMarker() {
        assertTrue(StepGuard.isBareName("auth.claims.cognito:groups"));
        assertTrue(StepGuard.isBareName("items.0"));
        assertTrue(StepGuard.isBareName("2fa-enabled"));
        assertFalse(StepGuard.isBareName("1"));
        assertFalse(StepGuard.isBareName("true"));
        assertFalse(StepGuard.isBareName("a == b"));
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

    @Test
    public void subtractedNamesShouldListNamesJoinedByMinus() {
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("get-ship.count > 0"));
        assertEquals(List.of(List.of("a", "b", "c"), List.of("a", "b")),
                StepGuard.subtractedNames("a-b-c == 1"));
    }

    @Test
    public void subtractedNamesShouldIgnoreBareNamesAndOtherSubtractions() {
        assertTrue(StepGuard.subtractedNames("get-ship.count").isEmpty(),
                "a bare name reads the dashed name");
        assertTrue(StepGuard.subtractedNames("total - 1 > 0").isEmpty());
        assertTrue(StepGuard.subtractedNames("a.b - c > 0").isEmpty());
    }

    @Test
    public void subtractedNamesShouldFollowMethodCallsAndMacros() {
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("get-ship.crew.size() > 0"));
        assertEquals(List.of(List.of("list", "merged")),
                StepGuard.subtractedNames("size(list-merged.filter(pr, pr.closed)) > 0"));
    }

    /** Two lists of 30 numbers: a macro nested in another evaluates 900 pairs. */
    private static Map<String, Object> twoLists() {
        List<Integer> numbers = java.util.stream.IntStream.range(0, 30).boxed().toList();
        return Map.of("prs", numbers, "issues", numbers);
    }

    @Test
    public void holdsShouldFailAGuardThatEvaluatesMoreThanItsLimit() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("publish",
                        "!prs.exists(p, issues.exists(i, i == p + 100))", twoLists(), 1_000));

        assertTrue(error.getMessage().startsWith("The onlyIf of step 'publish' evaluated more"
                + " than 1000 sub-expressions"), error.getMessage());
        assertTrue(error.getMessage().contains("management.guards.evaluationLimit"),
                error.getMessage());
    }

    @Test
    public void holdsShouldEvaluateANestedMacroWithinItsLimit() {
        assertTrue(StepGuard.holds("publish", "!prs.exists(p, issues.exists(i, i == p + 100))",
                twoLists(), 100_000));
    }

    @Test
    public void holdsShouldCountTheItemsOfAListReadInsideAMacro() {
        List<Integer> numbers = java.util.stream.IntStream.range(0, 2_000).boxed().toList();
        Map<String, Object> run = Map.of("prs", numbers, "blockers", numbers);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StepGuard.holds("publish", "prs.exists(p, p + 2000 in blockers)", run,
                        100_000));

        assertTrue(error.getMessage().contains("management.guards.evaluationLimit"),
                error.getMessage());
    }

    @Test
    public void holdsShouldFilterThousandsOfObjectsWithinTheDefaultLimit() {
        Map<String, Object> ship = new HashMap<>();
        for (int field = 0; field < 30; field++) {
            ship.put("field" + field, field);
        }
        ship.put("status", "active");
        List<Map<String, Object>> ships = java.util.Collections.nCopies(3_000, ship);

        assertTrue(StepGuard.holds("publish",
                "size(ships.filter(s, s.status == 'active')) > 0"
                        + " && ships.map(s, s.status).exists(x, x == 'active')",
                Map.of("ships", ships), 100_000));
    }

    @Test
    public void holdsShouldNotChargeAWholeMapForOneFieldReadInsideAMacro() {
        Map<String, Object> ship = new HashMap<>();
        for (int field = 0; field < 60; field++) {
            ship.put("field" + field, field);
        }
        ship.put("captainId", "c-1");
        ship.put("specs", new HashMap<>(ship));
        List<Map<String, Object>> crew = java.util.Collections.nCopies(3_000, Map.of("id", "c-2"));

        assertFalse(StepGuard.holds("publish",
                "crew.exists(c, c.id == get_ship.captainId)"
                        + " || crew.exists(c, get_ship.specs.captainId == c.id)",
                params("crew", crew, "get-ship", ship), 100_000));
    }

    @Test
    public void holdsShouldChargeAComparisonOfListsTheShorterOne() {
        List<Integer> lines = java.util.stream.IntStream.range(0, 30).boxed().toList();
        List<Map<String, Object>> orders =
                java.util.Collections.nCopies(5_000, Map.<String, Object>of("lines", lines));

        assertFalse(StepGuard.holds("publish", "orders.exists(o, o.lines == [])",
                Map.of("orders", orders), 100_000));
    }

    @Test
    public void holdsShouldIterateBeyondOneHundredThousandItemsWhenTheLimitIsRaised() {
        List<Integer> items = java.util.stream.IntStream.range(0, 150_000).boxed().toList();

        assertTrue(StepGuard.holds("publish", "items.all(i, i >= 0)", Map.of("items", items),
                10_000_000));
    }

    @Test
    public void holdsShouldReadADashedNameWrittenWithAnUnderscoreInABareName() {
        Map<String, Object> run = params("get-ship", Map.of("assignedCrew", "C1"));

        assertTrue(StepGuard.holds("s", "get_ship.assignedCrew", run));
        assertFalse(StepGuard.holds("s", "get_ship.missing", run));
    }

    @Test
    public void subtractedNamesShouldReadThroughANegationAndAnIndex() {
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("!get-ship.active"));
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("-get-ship.count < 0"));
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("get-ship['count'] > 0"));
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("get-ship.crew[0].name == 'x'"));
    }

    @Test
    public void parseErrorShouldHintAtANegatedDashedName() {
        String error = StepGuard.parseError("!get-ship.active");

        assertNotNull(error);
        assertTrue(error.endsWith("\n'-' is minus in CEL: if 'get-ship' is a name, write it"
                + " 'get_ship'"), error);
    }

    @Test
    public void parseErrorShouldHintAtTheSubtractionCelRefused() {
        String error = StepGuard.parseError("count - offset > 0 && !get-ship.active");

        assertNotNull(error);
        assertTrue(error.endsWith("if 'get-ship' is a name, write it 'get_ship'"), error);
    }

    @Test
    public void subtractedNamesShouldReadTheNameBeforeTheMinusThroughAConcatenation() {
        assertEquals(List.of(List.of("get", "ship")),
                StepGuard.subtractedNames("'p' + get-ship.name == 'x'"));
    }

    @Test
    public void parseErrorShouldHintAtADashedNameAfterAConcatenation() {
        String error = StepGuard.parseError("count - offset > 0 && 'p' + get-ship.name == 'x'");

        assertNotNull(error);
        assertTrue(error.endsWith("if 'get-ship' is a name, write it 'get_ship'"), error);
    }

    @Test
    public void parseErrorShouldNotHintWhenTheRefusedSubtractionIsNotOfNames() {
        String error = StepGuard.parseError("count - offset > 0 && 'p' - 1 > 0");

        assertNotNull(error);
        assertFalse(error.contains("is minus in CEL"), error);
    }

    @Test
    public void parseErrorShouldHintAtADashedNameInHas() {
        String error = StepGuard.parseError("has(get-ship.status)");

        assertNotNull(error);
        assertTrue(error.endsWith("if 'get-ship' is a name, write it 'get_ship'"), error);
    }

    @Test
    public void parseErrorShouldNotHintWhenTheErrorIsNotAboutASubtraction() {
        String error = StepGuard.parseError("a - b > 0 && sizee(x)");

        assertNotNull(error);
        assertFalse(error.contains("is minus in CEL"), error);
    }

    @Test
    public void parseErrorShouldHintAtADashedNameWrittenWithItsDash() {
        String error = StepGuard.parseError("size(list-merged.filter(pr, pr.closed)) > 0");

        assertNotNull(error);
        assertTrue(error.endsWith("if 'list-merged' is a name, write it 'list_merged'"), error);
    }
}
