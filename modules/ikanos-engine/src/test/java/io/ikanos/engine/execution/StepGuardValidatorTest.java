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
    void findErrorsShouldReportATrackThatReadsAnotherTrack() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: merged
                      type: call
                      what: github.list-merged
                    - name: blockers
                      type: call
                      what: github.list-blockers
                      onlyIf: merged
                      with:
                        since: "{{merged.date}}"
                """));

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("reads track 'merged'"), errors.get(0));
        assertTrue(errors.get(1).startsWith("Step 'blockers'")
                && errors.get(1).contains("reads track 'merged'"), errors.get(1));
    }

    @Test
    void findErrorsShouldReportASequenceOutsideATrackAndAnElicitInsideOne() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: loose
                  type: sequence
                  steps:
                    - name: inner
                      type: call
                      what: a.b
                - name: gather
                  type: parallel
                  tracks:
                    - name: confirm
                      type: elicit
                      fields:
                        ok:
                          type: boolean
                    - name: other
                      type: call
                      what: a.c
                """));

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("only allowed as a track"), errors.get(0));
        assertTrue(errors.get(1).contains("inside a parallel track"), errors.get(1));
    }

    @Test
    void findWarningsShouldLetADoReadItsItemAndTheStepsBeforeIt() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: merged
                  type: call
                  what: github.list-merged
                - name: issues
                  type: forEach
                  items: merged
                  as: pr
                  limit: 50
                  do:
                    type: call
                    what: github.get-issue
                    onlyIf: "pr.number > 0 && order != null"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findErrorsShouldHintAtADashedNameInItemsThatDoesNotCompile() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: list-merged
                  type: call
                  what: github.list-merged
                - name: issues
                  type: forEach
                  items: "list-merged.filter(pr, pr.closed)"
                  limit: 50
                  do:
                    type: call
                    what: github.get-issue
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("The items of step 'issues'")
                && errors.get(0).endsWith("write it 'list_merged'"), errors.get(0));
    }

    @Test
    void findWarningsShouldReportADashedNameReadAsASubtractionInALimit() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: page-size
                  type: call
                  what: github.page-size
                - name: lines
                  type: forEach
                  items: order.lines
                  limit: "page-size + 1"
                  do:
                    type: call
                    what: shop.check-line
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("The limit of step 'lines'")
                && warnings.get(0).contains("write 'page_size'"), warnings.get(0));
    }

    @Test
    void findErrorsShouldCheckTheGuardOfASequenceDo() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: lines
                  type: forEach
                  items: order.lines
                  do:
                    type: sequence
                    onlyIf: "item.qty =="
                    steps:
                      - name: check
                        type: call
                        what: shop.check-line
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("not a valid CEL expression"), errors.get(0));
    }

    @Test
    void findWarningsShouldReportAForEachOverAStepWithNoLimit() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: merged
                  type: call
                  what: github.list-merged
                - name: issues
                  type: forEach
                  items: merged
                  do:
                    type: call
                    what: github.get-issue
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("with no limit"), warnings.get(0));
    }

    @Test
    void findWarningsShouldReportAForEachOverATrackWithNoLimit() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: merged
                      type: call
                      what: github.list-merged
                    - name: other
                      type: call
                      what: github.list-other
                - name: issues
                  type: forEach
                  items: merged
                  do:
                    type: call
                    what: github.get-issue
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("with no limit"), warnings.get(0));
    }

    private static List<String> forEachErrors(String as, String limit) {
        return StepGuardValidator.findErrors(flow("""
                - name: lines
                  type: forEach
                  items: order.lines
                  as: %s
                  limit: "%s"
                  do:
                    type: call
                    what: shop.check-line
                """.formatted(as, limit)));
    }

    @Test
    void findErrorsShouldReportAnItemNamedOutcome() {
        List<String> errors = forEachErrors("outcome", "50");

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("item 'outcome'"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportAnItemNamedAuth() {
        List<String> errors = forEachErrors("auth", "50");

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("item 'auth'"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportALimitThatDoesNotParse() {
        List<String> errors = forEachErrors("line", "max +");

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("The limit of step 'lines'"), errors.get(0));
    }

    @Test
    void findWarningsShouldNotAskForALimitOverAnInput() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: lines
                  type: forEach
                  items: order.lines
                  do:
                    type: call
                    what: shop.check-line
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findErrorsShouldReportItemsThatDoNotParseAndAQuestionInADo() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: lines
                  type: forEach
                  items: "order.lines.filter("
                  do:
                    type: elicit
                    fields:
                      ok:
                        type: boolean
                """));

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("The items of step 'lines'"), errors.get(0));
        assertTrue(errors.get(1).contains("forEach step"), errors.get(1));
    }

    @Test
    void findWarningsShouldLetEachPathReadTheStepsBeforeTheChoice() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: issue
                  type: call
                  what: github.get-issue
                - name: route
                  type: choice
                  if: "issue.label == 'bug'"
                  then:
                    type: call
                    what: notion.create-incident
                    onlyIf: issue.title
                  else:
                    type: choice
                    if: "issue.label == 'feature'"
                    then:
                      type: call
                      what: notion.add-row
                    else:
                      type: call
                      what: github.ask-details
                - name: after
                  type: call
                  what: log.write
                  onlyIf: route
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findErrorsShouldReportAChoiceWithoutAnIf() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: route
                  type: choice
                  then:
                    type: call
                    what: notion.create-incident
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("without an 'if'"), errors.get(0));
    }

    @Test
    void findWarningsShouldReportADashedNameReadAsASubtractionInAChoiceIf() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: get-issue
                  type: call
                  what: github.get-issue
                - name: route
                  type: choice
                  if: "get-issue.label == 'bug'"
                  then:
                    type: call
                    what: notion.create-incident-page
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("The if of step 'route'")
                && warnings.get(0).contains("write 'get_issue'"), warnings.get(0));
    }

    @Test
    void findErrorsShouldReportAChoiceWithoutAThen() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: route
                  type: choice
                  if: issue
                  else:
                    type: call
                    what: github.ask-details
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("without a 'then' path"), errors.get(0));
    }

    @Test
    void findWarningsShouldSayThenNeverRunsWhenAChoiceWithoutElseReadsNothing() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: route
                  type: choice
                  if: missing
                  then:
                    type: call
                    what: notion.create-incident
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).endsWith("the then path never runs."), warnings.get(0));
    }

    @Test
    void findErrorsShouldReportAnIfThatDoesNotParse() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: route
                  type: choice
                  if: "issue.label =="
                  then:
                    type: call
                    what: notion.create-incident
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("The if of step 'route'"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportAQuestionInAPath() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: route
                  type: choice
                  if: issue
                  then:
                    type: elicit
                    fields:
                      ok:
                        type: boolean
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("or a choice"), errors.get(0));
    }

    @Test
    void findWarningsShouldReportAnIfReadingANameNothingProvides() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: route
                  type: choice
                  if: "isue.label == 'bug'"
                  then:
                    type: call
                    what: notion.create-incident
                """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("reads 'isue'"), warnings.get(0));
    }

    @Test
    void findErrorsShouldReportASequenceInsideASequence() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: a
                      type: sequence
                      steps:
                        - name: inner
                          type: sequence
                          steps:
                            - name: deep
                              type: call
                              what: x.y
                    - name: b
                      type: call
                      what: x.z
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Step 'inner'"), errors.get(0));
    }

    @Test
    void findErrorsShouldCheckTheGuardOfASequenceTrack() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: a
                      type: call
                      what: x.y
                    - name: b
                      type: sequence
                      onlyIf: a
                      steps:
                        - name: b1
                          type: call
                          what: x.z
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("reads track 'a'"), errors.get(0));
    }

    @Test
    void findErrorsShouldNotTakeAPlainWithValueForATrackRead() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: billing
                      type: call
                      what: crm.get-account
                    - name: region
                      type: call
                      what: crm.get-region
                      with:
                        zone: billing.eu
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldReportATrackNamedLikeAStepOfTheSameList() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: profile
                  type: call
                  what: crm.get-profile
                - name: gather
                  type: parallel
                  tracks:
                    - name: profile
                      type: call
                      what: crm.get-profile
                    - name: tickets
                      type: call
                      what: crm.list-tickets
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Track 'profile'"), errors.get(0));
    }

    @Test
    void findWarningsShouldLetTheStepsAfterAGroupReadItsTracks() {
        List<String> warnings = StepGuardValidator.findWarnings(flow("""
                - name: gather
                  type: parallel
                  tracks:
                    - name: merged
                      type: call
                      what: github.list-merged
                    - name: billing
                      type: sequence
                      steps:
                        - name: account
                          type: call
                          what: crm.get-account
                        - name: invoices
                          type: call
                          what: crm.get-invoices
                          onlyIf: account
                - name: publish
                  type: call
                  what: notion.publish
                  onlyIf: "merged != null && billing.open > 0 && outcome.merged == 'success'"
                """));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** {@code depth} parallel steps, each holding the next in a sequence track. */
    private static String nestedParallels(int depth) {
        String inner = """
                - name: deep
                  type: call
                  what: x.y
                """;
        for (int level = depth; level >= 1; level--) {
            inner = """
                    - name: p%d
                      type: parallel
                      tracks:
                        - name: t%d
                          type: sequence
                          steps:
                    %s    - name: side%d
                          type: call
                          what: x.z
                    """.formatted(level, level, inner.indent(8), level);
        }
        return inner;
    }

    @Test
    void findWarningsShouldAcceptThreeNestedParallelSteps() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(nestedParallels(3)));

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void findWarningsShouldReportNestingDeeperThanThreeLevels() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(nestedParallels(4)));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("more than 3 levels")),
                warnings.toString());
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

    @Test
    void findErrorsShouldReportAnEndInsideATrack() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: group
                  type: parallel
                  tracks:
                    - name: stop
                      type: end
                - name: other
                  type: parallel
                  tracks:
                    - name: billing
                      type: sequence
                      steps:
                        - name: invoices
                          type: call
                          what: crm.get-invoices
                        - name: done
                          type: end
                """));

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.stream().allMatch(e -> e.contains("ends the flow from inside a"
                + " parallel track")), errors.toString());
    }

    @Test
    void findErrorsShouldReportATrackReadingAnotherThroughItsOutcomeOrAValue() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: group
                  type: parallel
                  tracks:
                    - name: profile
                      type: call
                      what: crm.get-profile
                    - name: by-outcome
                      type: call
                      what: crm.a
                      onlyIf: "outcome.profile == 'success'"
                    - name: by-name
                      type: call
                      what: crm.b
                      with:
                        who: customers.profile
                    - name: nested
                      type: call
                      what: crm.c
                      with:
                        body:
                          id: "{{profile.id}}"
                    - name: lookup
                      type: lookup
                      index: profile
                      match: id
                      lookupValue: "$.profile.id"
                      outputParameters:
                        - name
                """));

        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.stream().allMatch(e -> e.contains("track 'profile'")),
                errors.toString());
    }

    @Test
    void findErrorsShouldReportTwoGroupsWithATrackOfTheSameName() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: first
                  type: parallel
                  tracks:
                    - name: profile
                      type: call
                      what: crm.get-profile
                - name: second
                  type: parallel
                  tracks:
                    - name: profile
                      type: call
                      what: crm.get-other-profile
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("has the name of a track of parallel step 'first'"),
                errors.get(0));
    }

    @Test
    void findErrorsShouldReportAnEndInsideADo() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: issues
                  type: forEach
                  items: order.lines
                  do:
                    type: sequence
                    steps:
                      - name: a
                        type: call
                        what: shop.a
                      - name: stop
                        type: end
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("ends the flow from inside a do"), errors.get(0));
    }

    /**
     * A parallel step whose track holds a forEach whose sequence do holds another forEach:
     * three constructs, the most the memo allows.
     */
    private static final String THREE_LEVELS = """
            - name: group
              type: parallel
              tracks:
                - name: lines
                  type: forEach
                  items: order.lines
                  as: line
                  limit: 10
                  do:
                    type: sequence
                    steps:
                      - name: parts
                        type: forEach
                        items: line.parts
                        as: part
                        limit: 10
                        do:
                          type: call
                          what: shop.check-part
                - name: other
                  type: call
                  what: shop.other
            """;

    @Test
    void findWarningsShouldAcceptAForEachInAForEachInAParallelStep() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(THREE_LEVELS));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("levels deep")),
                warnings.toString());
    }

    @Test
    void findWarningsShouldReportAForEachDoPastThreeLevels() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(THREE_LEVELS.replace(
                "              type: call\n              what: shop.check-part\n",
                "              type: forEach\n              items: part.screws\n"
                        + "              as: screw\n              limit: 10\n"
                        + "              do:\n                type: call\n"
                        + "                what: shop.check-screw\n")));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("more than 3 levels")),
                warnings.toString());
    }

    @Test
    void findErrorsShouldAcceptAnEndInAChoicePath() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: route
                  type: choice
                  if: "order.status == 'paid'"
                  then:
                    type: end
                - name: remind
                  type: call
                  what: shop.remind
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldReportAnEndInAChoicePathInsideADo() {
        List<String> errors = StepGuardValidator.findErrors(flow("""
                - name: lines
                  type: forEach
                  items: order.lines
                  as: line
                  do:
                    type: choice
                    if: "line.qty == 0"
                    then:
                      type: end
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("ends the flow from inside a do"), errors.get(0));
    }

    /** The memo's "choice inside an iteration inside a parallel group", single-step paths. */
    private static final String CHOICE_IN_FOREACH_IN_PARALLEL = """
            - name: group
              type: parallel
              tracks:
                - name: lines
                  type: forEach
                  items: order.lines
                  as: line
                  limit: 10
                  do:
                    type: choice
                    if: "line.qty > 0"
                    then:
                      type: call
                      what: shop.reserve
                    else:
                      type: call
                      what: shop.skip
                - name: other
                  type: call
                  what: shop.other
            """;

    @Test
    void findWarningsShouldAcceptAChoiceInAForEachInAParallelStep() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(CHOICE_IN_FOREACH_IN_PARALLEL));

        assertTrue(warnings.stream().noneMatch(w -> w.contains("levels deep")),
                warnings.toString());
    }

    @Test
    void findWarningsShouldReportAChoicePathPastThreeLevels() {
        List<String> warnings = StepGuardValidator.findWarnings(flow(
                CHOICE_IN_FOREACH_IN_PARALLEL.replace(
                        "        then:\n          type: call\n"
                                + "          what: shop.reserve\n",
                        "        then:\n          type: sequence\n"
                                + "          steps:\n"
                                + "            - name: inner\n"
                                + "              type: choice\n"
                                + "              if: \"line.urgent\"\n"
                                + "              then:\n"
                                + "                type: call\n"
                                + "                what: shop.rush\n")));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("more than 3 levels")),
                warnings.toString());
    }
}
