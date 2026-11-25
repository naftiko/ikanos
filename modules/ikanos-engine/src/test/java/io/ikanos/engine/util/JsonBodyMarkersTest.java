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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for {@link JsonBodyMarkers} (#1132): the load-time check of the markers and the
 * {@code $onlyIf} test.
 */
class JsonBodyMarkersTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    @Test
    void findErrorsShouldAcceptWellFormedMarkers() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $first:
                    - {$onlyIf: icon_emoji, type: emoji, emoji: "{{icon_emoji}}"}
                    - {$onlyIf: icon_url, type: external, external: {url: "{{icon_url}}"}}
                    - {type: none}
                filter: {$keep: true, team: "{{team}}"}
                content: {$onlyIf: auth.claims.sub, brief: true}
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectOnlyIfThatIsAPlaceholder() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                notifications: {$onlyIf: "{{silent}}", mode: silent}
                """));

        assertEquals(List.of("/notifications: $onlyIf must name an input, without braces, such as"
                + " '$onlyIf: silent'"), errors);
    }

    @Test
    void findErrorsShouldRejectOnlyIfThatIsADotWithoutAName() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                a: {$onlyIf: ".", mode: silent}
                b: {$onlyIf: "silent.", mode: silent}
                c: {$onlyIf: "auth..sub", mode: silent}
                """));

        assertEquals(3, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectOnlyIfThatIsNotAString() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                notifications: {$onlyIf: true, mode: silent}
                """));

        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectKeepThatIsNotTrue() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                filter: {$keep: "yes", team: "{{team}}"}
                """));

        assertEquals(List.of("/filter: $keep must be true; leave it out otherwise"), errors);
    }

    @Test
    void findErrorsShouldRejectFirstWithSiblingMembers() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $onlyIf: icon
                  $first: [{type: emoji}]
                """));

        assertEquals(List.of("/icon: an object with $first has no other member; put $onlyIf,"
                + " $keep and the members in its variants"), errors);
    }

    @Test
    void findErrorsShouldRejectFirstThatIsNotAList() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon: {$first: {type: emoji}}
                """));

        assertEquals(List.of("/icon: $first must be a non-empty list of objects"), errors);
    }

    @Test
    void findErrorsShouldRejectVariantThatIsNotAnObject() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon: {$first: ["{{icon}}"]}
                """));

        assertEquals(List.of("/icon/$first/0: a $first variant must be an object"), errors);
    }

    @Test
    void findErrorsShouldRejectVariantAfterTheFallback() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $first:
                    - {type: none}
                    - {$onlyIf: icon_url, type: external}
                """));

        assertEquals(List.of("/icon/$first/1: never used, since a variant before it has no"
                + " $onlyIf"), errors);
    }

    @Test
    void findErrorsShouldCheckMarkersInsideVariantsAndArrays() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $first:
                    - {$onlyIf: icon_url, external: {$keep: 1}}
                children:
                  - {$onlyIf: "", type: paragraph}
                """));

        assertEquals(List.of("/icon/$first/0/external: $keep must be true; leave it out"
                + " otherwise", "/children/0: $onlyIf must name an input, without braces, such as"
                + " '$onlyIf: silent'"), errors);
    }

    @Test
    void findErrorsShouldReportMarkersOfTheRootObject() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                $keep: false
                name: "{{name}}"
                """));

        assertEquals(List.of("/: $keep must be true; leave it out otherwise"), errors);
    }

    @Test
    void findErrorsShouldAcceptOnlyIfThatNamesANamespacedClaim() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                admin: {$onlyIf: "auth.claims.https://example.com/role", include: true}
                groups: {$onlyIf: "auth.claims.cognito:groups", include: true}
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectOnlyIfOnTheRootObject() throws Exception {
        // The body is always sent, so an $onlyIf on the root could never leave it out
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                $onlyIf: silent
                name: "{{name}}"
                """));

        assertEquals(List.of("/: $onlyIf cannot be on the root object, since the body is always"
                + " sent; put it on a member"), errors);
    }

    @Test
    void findErrorsShouldIgnoreEscapedMarkerKeys() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                query: {$$onlyIf: {$$first: 1}, $ref: "#/x"}
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void conditionsShouldListEveryOnlyIfNameOnce() throws Exception {
        Set<String> names = JsonBodyMarkers.conditions(data("""
                icon:
                  $first:
                    - {$onlyIf: icon_emoji, type: emoji}
                    - {$onlyIf: icon_url, type: external}
                cover: {$onlyIf: icon_url, type: external}
                children: [{$onlyIf: silent}]
                """));

        assertEquals(List.of("icon_emoji", "icon_url", "silent"), List.copyOf(names));
    }

    @Test
    void appliesShouldHoldForPresentValuesOtherThanFalse() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("empty", "");
        parameters.put("zero", 0);
        parameters.put("text", "false");
        parameters.put("no", false);
        parameters.put("nothing", null);

        assertTrue(JsonBodyMarkers.applies(Map.of("$onlyIf", "empty"), parameters));
        assertTrue(JsonBodyMarkers.applies(Map.of("$onlyIf", "zero"), parameters));
        assertTrue(JsonBodyMarkers.applies(Map.of("$onlyIf", "text"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$onlyIf", "no"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$onlyIf", "nothing"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$onlyIf", "missing"), parameters));
    }

    @Test
    void appliesShouldReadDottedNamesThroughNestedValues() {
        Map<String, Object> parameters = Map.of("step", Map.of("id", "42"));

        assertTrue(JsonBodyMarkers.applies(Map.of("$onlyIf", "step.id"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$onlyIf", "step.name"), parameters));
    }

    @Test
    void appliesShouldFailTheCallWhenTheCallerClaimIsMissing() {
        // As in a placeholder (#998): a missing claim fails, it does not leave the object out
        assertThrows(CallerClaimMissingException.class, () -> JsonBodyMarkers.applies(
                Map.of("$onlyIf", "auth.claims.sub"), new HashMap<>()));
    }

    @Test
    void appliesShouldHoldWhenObjectHasNoOnlyIf() {
        assertTrue(JsonBodyMarkers.applies(Map.of("mode", "silent"), null));
    }

    @Test
    void appliesShouldRejectOnlyIfThatDoesNotNameAnInput() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonBodyMarkers.applies(Map.of("$onlyIf", "{{x}}"), Map.of("x", 1)));
    }

    @Test
    void unescapeShouldRemoveOneDollarFromAnEscapedKey() {
        assertEquals("$onlyIf", JsonBodyMarkers.unescape("$$onlyIf"));
        assertEquals("$ref", JsonBodyMarkers.unescape("$ref"));
        assertEquals("$$x", JsonBodyMarkers.unescape("$$$x"));
    }

    private static Object data(String yaml) throws Exception {
        return YAML.readValue(yaml, Object.class);
    }
}
