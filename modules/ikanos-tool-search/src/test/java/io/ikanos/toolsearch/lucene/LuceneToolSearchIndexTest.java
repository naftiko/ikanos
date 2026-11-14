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
package io.ikanos.toolsearch.lucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.ikanos.engine.exposes.mcp.search.ToolSearchDocument;
import io.ikanos.engine.exposes.mcp.search.ToolSearchQuery;

class LuceneToolSearchIndexTest {

    private static final List<ToolSearchDocument> TOOLS = List.of(
            new ToolSearchDocument("get-incident", null,
                    "Get an incident by its id, with status, severity and owner.",
                    List.of("incidents"), "incident-id Identifier of the incident.", true),
            new ToolSearchDocument("restart-deployment", "Restart deployment",
                    "Restart a Kubernetes deployment and wait for the rollout to finish.",
                    List.of("kubernetes", "deployments"), "namespace deployment", false),
            new ToolSearchDocument("list-deployments", null,
                    "List the deployments of a Kubernetes namespace.",
                    List.of("kubernetes", "deployments"), "namespace Kubernetes namespace.", true),
            new ToolSearchDocument("issue-refund", null,
                    "Refund all or part of a paid invoice.",
                    List.of("billing"), "invoice-id amount", false),
            new ToolSearchDocument("get-customer", null,
                    "Get a customer account by email or id.",
                    List.of("customers"), "customer Customer email or id.", true));

    private final LuceneToolSearchIndex index = new LuceneToolSearchIndex(TOOLS);

    private List<String> search(String text) {
        return index.search(new ToolSearchQuery(text, Set.of(), false, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"restart-deployment", "restartDeployment", "restart_deployment",
        "Restart Deployment"})
    void searchShouldFindTheSameToolWhateverTheWordSeparator(String text) {
        assertEquals("restart-deployment", search(text).getFirst());
    }

    @Test
    void searchShouldMatchStemmedWords() {
        assertEquals("issue-refund", search("refunding invoices").getFirst());
    }

    @Test
    void searchShouldRankAnExactToolNameFirst() {
        assertEquals("list-deployments", search("list-deployments").getFirst());
    }

    @Test
    void searchShouldKeepOnlyToolsCarryingOneOfTheTags() {
        List<String> names = index.search(
                new ToolSearchQuery("get", Set.of("customers", "billing"), false, 5));

        assertEquals(List.of("get-customer"), names);
    }

    @Test
    void searchShouldKeepOnlyReadOnlyToolsWhenAsked() {
        List<String> names = index.search(new ToolSearchQuery("deployment", Set.of(), true, 5));

        assertEquals(List.of("list-deployments"), names);
    }

    @Test
    void searchShouldReturnAtMostTheLimit() {
        assertEquals(1, index.search(new ToolSearchQuery("get", Set.of(), false, 1)).size());
    }

    @Test
    void searchShouldReturnNothingWhenNoWordMatches() {
        assertEquals(List.of(), search("photosynthesis"));
    }

    @Test
    void searchShouldReturnNothingForATextWithoutWords() {
        assertEquals(List.of(), search(" -- !! "));
    }

    @Test
    void searchShouldBreakScoreTiesByToolName() {
        LuceneToolSearchIndex twins = new LuceneToolSearchIndex(List.of(
                new ToolSearchDocument("zeta-report", null, "Weekly report.", List.of(), "", true),
                new ToolSearchDocument("alpha-report", null, "Weekly report.", List.of(), "", true)));

        assertEquals(List.of("alpha-report", "zeta-report"),
                twins.search(new ToolSearchQuery("weekly", Set.of(), false, 5)));
    }

    @Test
    void searchShouldReturnTheSameNamesForTheSameQuery() {
        assertEquals(search("kubernetes namespace deployments"),
                search("kubernetes namespace deployments"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name:*", "\"unbalanced", "deployment AND NOT refund", "tags:billing",
        "(restart", "restart~2", "*:*"})
    void searchShouldTreatQuerySyntaxAsPlainWords(String text) {
        List<String> names = search(text);

        assertTrue(names.size() <= 5, "a model's text must never fail or widen the search");
    }

    @Test
    void searchShouldNotReachAnotherFieldThroughAFieldPrefix() {
        assertEquals(List.of(), search("tags:billing").stream()
                .filter(name -> !name.equals("issue-refund")).toList(),
                "'tags:billing' is two words, not a query on the tags field");
    }
}
