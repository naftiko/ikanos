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
 * {@code $when} test.
 */
class JsonBodyMarkersTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    @Test
    void findErrorsShouldAcceptWellFormedMarkers() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $first:
                    - {$when: icon_emoji, type: emoji, emoji: "{{icon_emoji}}"}
                    - {$when: icon_url, type: external, external: {url: "{{icon_url}}"}}
                    - {type: none}
                filter: {$keep: true, team: "{{team}}"}
                content: {$when: auth.claims.sub, brief: true}
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectWhenThatIsAPlaceholder() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                notifications: {$when: "{{silent}}", mode: silent}
                """));

        assertEquals(List.of("/notifications: $when must name an input, without braces, such as"
                + " '$when: silent'"), errors);
    }

    @Test
    void findErrorsShouldRejectWhenThatIsADotWithoutAName() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                a: {$when: ".", mode: silent}
                b: {$when: "silent.", mode: silent}
                c: {$when: "auth..sub", mode: silent}
                """));

        assertEquals(3, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldRejectWhenThatIsNotAString() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                notifications: {$when: true, mode: silent}
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
                  $when: icon
                  $first: [{type: emoji}]
                """));

        assertEquals(List.of("/icon: an object with $first has no other member; put $when,"
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
                    - {$when: icon_url, type: external}
                """));

        assertEquals(List.of("/icon/$first/1: never used, since a variant before it has no"
                + " $when"), errors);
    }

    @Test
    void findErrorsShouldCheckMarkersInsideVariantsAndArrays() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                icon:
                  $first:
                    - {$when: icon_url, external: {$keep: 1}}
                children:
                  - {$when: "", type: paragraph}
                """));

        assertEquals(List.of("/icon/$first/0/external: $keep must be true; leave it out"
                + " otherwise", "/children/0: $when must name an input, without braces, such as"
                + " '$when: silent'"), errors);
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
    void findErrorsShouldRejectWhenOnTheRootObject() throws Exception {
        // The body is always sent, so a $when on the root could never leave it out
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                $when: silent
                name: "{{name}}"
                """));

        assertEquals(List.of("/: $when cannot be on the root object, since the body is always"
                + " sent; put it on a member"), errors);
    }

    @Test
    void findErrorsShouldIgnoreEscapedMarkerKeys() throws Exception {
        List<String> errors = JsonBodyMarkers.findErrors(data("""
                query: {$$when: {$$first: 1}, $ref: "#/x"}
                """));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void conditionsShouldListEveryWhenNameOnce() throws Exception {
        Set<String> names = JsonBodyMarkers.conditions(data("""
                icon:
                  $first:
                    - {$when: icon_emoji, type: emoji}
                    - {$when: icon_url, type: external}
                cover: {$when: icon_url, type: external}
                children: [{$when: silent}]
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

        assertTrue(JsonBodyMarkers.applies(Map.of("$when", "empty"), parameters));
        assertTrue(JsonBodyMarkers.applies(Map.of("$when", "zero"), parameters));
        assertTrue(JsonBodyMarkers.applies(Map.of("$when", "text"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$when", "no"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$when", "nothing"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$when", "missing"), parameters));
    }

    @Test
    void appliesShouldReadDottedNamesThroughNestedValues() {
        Map<String, Object> parameters = Map.of("step", Map.of("id", "42"));

        assertTrue(JsonBodyMarkers.applies(Map.of("$when", "step.id"), parameters));
        assertFalse(JsonBodyMarkers.applies(Map.of("$when", "step.name"), parameters));
    }

    @Test
    void appliesShouldFailTheCallWhenTheCallerClaimIsMissing() {
        // As in a placeholder (#998): a missing claim fails, it does not leave the object out
        assertThrows(CallerClaimMissingException.class, () -> JsonBodyMarkers.applies(
                Map.of("$when", "auth.claims.sub"), new HashMap<>()));
    }

    @Test
    void appliesShouldHoldWhenObjectHasNoWhen() {
        assertTrue(JsonBodyMarkers.applies(Map.of("mode", "silent"), null));
    }

    @Test
    void appliesShouldRejectWhenThatDoesNotNameAnInput() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonBodyMarkers.applies(Map.of("$when", "{{x}}"), Map.of("x", 1)));
    }

    @Test
    void unescapeShouldRemoveOneDollarFromAnEscapedKey() {
        assertEquals("$when", JsonBodyMarkers.unescape("$$when"));
        assertEquals("$ref", JsonBodyMarkers.unescape("$ref"));
        assertEquals("$$x", JsonBodyMarkers.unescape("$$$x"));
    }

    private static Object data(String yaml) throws Exception {
        return YAML.readValue(yaml, Object.class);
    }
}
