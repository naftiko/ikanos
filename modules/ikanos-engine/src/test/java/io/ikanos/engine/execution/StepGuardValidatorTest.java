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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

public class StepGuardValidatorTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    /** A flow with one input, {@code order}, a {@code with} value and the given steps. */
    private static IkanosSpec flow(String steps) {
        return IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: billing
                      flows:
                        remind:
                          description: Remind the customer
                          inputParameters:
                            order:
                              type: object
                              description: The order
                          with:
                            channel: email
                          steps:
                %s
                """.formatted(IKANOS, steps.indent(12)), IkanosSpec.class);
    }

    @Test
    void findErrorsShouldReportAGuardThatDoesNotParse() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "order.status =="
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("step 'alert' in flow 'billing.remind'"),
                errors.get(0));
    }

    @Test
    void findErrorsShouldReportAGuardThatCallsAFunctionThatDoesNotExist() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "sizee(order.lines) > 0"
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("sizee"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportASuccessCriterionThatDoesNotParse() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: find
                  type: call
                  what: notion.find
                  successCriteria:
                    - "outcome.find =="
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("A success criterion of step 'find'"),
                errors.get(0));
    }

    @Test
    void findErrorsShouldReportAnOutcomeThatIsNotInTheVocabulary() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: find
                  type: call
                  what: notion.find
                  successCriteria:
                    - "outcome.find == 'success' || outcome['find'] == 'gone'"
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'gone', which is not one"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportAStepNamedOutcome() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: outcome
                  type: call
                  what: notion.find
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'outcome', which is reserved"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportAWithKeyNamedOutcome() {
        IkanosSpec spec = flow("""
                - name: find
                  type: call
                  what: notion.find
                """);
        spec.getCapability().getAggregates().values().iterator().next().getFlows()
                .get("remind").setWith(java.util.Map.of("outcome", "won"));

        List<String> errors = StepGuardValidator.findErrors(spec);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("An input or a 'with' key"), errors.get(0));
    }

    @Test
    void findWarningsShouldLetASuccessCriterionReadItsOwnStep() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: find
                  type: call
                  what: notion.find
                  successCriteria:
                    - "outcome.find == 'not-found' || find.id != ''"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findWarningsShouldReportStepsAnUnguardedEndMakesUnreachable() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: done
                  type: end
                - name: never
                  type: call
                  what: notion.find
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Step 'never'"), warnings.get(0));
    }

    @Test
    void validateShouldRefuseACapabilityWithAGuardThatDoesNotParse() {
        IkanosSpec spec = flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "(order"
                """);

        assertThrows(IllegalArgumentException.class, () -> StepGuardValidator.validate(spec));
    }

    @Test
    void findWarningsShouldAcceptInputsWithValuesAndEarlierSteps() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: get-order
                  type: call
                  what: shop.get-order
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "order.status == 'overdue' && channel == 'email' && get_order.ok"
                - name: audit
                  type: call
                  what: log.write
                  onlyIf: auth.claims.sub
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findWarningsShouldReportANameNothingProvides() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "ordr.status == 'overdue'"
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("reads 'ordr'"), warnings.get(0));
    }

    @Test
    void findWarningsShouldReportADashedNameReadAsASubtraction() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: get-ship
                  type: call
                  what: fleet.get-ship
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "get-ship.crew > 0"
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("reads 'get - ship', a subtraction")
                && warnings.get(0).contains("write 'get_ship'"), warnings.get(0));
    }

    @Test
    void findWarningsShouldAcceptASubtractionOfNamesThatNameNothingDashed() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: "order.total - order.paid > 0"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findWarningsShouldReportAStepReadBeforeItRuns() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: alert
                  type: call
                  what: notify.send
                  onlyIf: later
                - name: later
                  type: call
                  what: shop.get-order
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("reads 'later'"), warnings.get(0));
    }

    @Test
    void findWarningsShouldReportAGuardReadingTheOutcomeOfAStepThatDoesNotExist() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: find
                  type: call
                  what: notion.find-page
                  successCriteria:
                    - "outcome.find == 'success'"
                    - "outcome.find == 'not-found'"
                - name: create
                  type: call
                  what: notion.create-page
                  onlyIf: "outcome.fnd != 'success'"
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("reads the outcome of 'fnd', which no earlier step"
                + " has"), warnings.get(0));
    }

    @Test
    void findWarningsShouldReportAGuardReadingTheOutcomeOfAStepThatIsNotACall() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: pages
                  type: call
                  what: notion.list-pages
                - name: find
                  type: lookup
                  index: pages
                  match: id
                  lookupValue: $.order.page
                  outputParameters:
                    - title
                - name: create
                  type: call
                  what: notion.create-page
                  onlyIf: "outcome.find == 'success'"
                """));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("reads the outcome of 'find',"
                + " which no earlier step has")), warnings.toString());
    }

    @Test
    void findWarningsShouldReportAGuardReadingTheOutcomeOfALaterStep() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: create
                  type: call
                  what: notion.create-page
                  onlyIf: "outcome['find'] == 'not-found'"
                - name: find
                  type: call
                  what: notion.find-page
                """));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("reads the outcome of 'find'")),
                warnings.toString());
    }

    @Test
    void findWarningsShouldAcceptACriterionOnItsOwnStepAndAGuardOnAnEarlierOne() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: find-page
                  type: call
                  what: notion.find-page
                  successCriteria:
                    - "outcome.find_page == 'not-found'"
                - name: create
                  type: call
                  what: notion.create-page
                  onlyIf: "outcome.find_page == 'not-found'"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
