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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.ikanos.engine.util.Resolver;

/**
 * Unit tests for {@link FormUrlEncodedBody}: the map and pre-encoded string forms of a
 * {@code formUrlEncoded} request body.
 */
class FormUrlEncodedBodyTest {

    @Test
    void encodeShouldRejectUnresolvedTemplateWhenNoParametersAreGiven() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FormUrlEncodedBody.encode(Map.of("name", "{{missing}}"), Map.of()));

        assertTrue(error.getMessage().contains("form field 'name'"));
    }

    @Test
    void encodeShouldSendMissingVariableAsEmptyValueWhenOtherParametersAreGiven() {
        // Pins current behavior: with any parameter present, Resolver renders a missing variable
        // as an empty string (JMustache defaultValue). Update this test if that changes.
        assertEquals("name=",
                FormUrlEncodedBody.encode(Map.of("name", "{{missing}}"), Map.of("other", "x")));
    }

    @Test
    void encodeShouldPreserveKeyOrderAndEncodeEmptyValues() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("b", "2");
        data.put("a", null);

        assertEquals("b=2&a=", FormUrlEncodedBody.encode(data, Map.of("x", "y")));
    }

    @Test
    void encodeShouldSendSubstitutedValueContainingBracesAsData() {
        assertEquals("q=%7B%7Bx%7D%7D",
                FormUrlEncodedBody.encode(Map.of("q", "{{v}}"), Map.of("v", "{{x}}")));
    }

    @Test
    void resolveShouldEncodeOnlySubstitutedValues() {
        assertEquals("a=%2F&q=read%26x%3D1",
                FormUrlEncodedBody.resolve("a=%2F&q={{v}}", Map.of("v", "read&x=1")));
    }

    @Test
    void resolveShouldEncodeSubstitutedValueContainingBracesAsData() {
        assertEquals("q=%7B%7Bx%7D%7D",
                FormUrlEncodedBody.resolve("q={{v}}", Map.of("v", "{{x}}")));
    }

    @Test
    void resolveShouldJsonSerializeListValuesBeforeEncoding() {
        // Same serialization as Resolver.resolveMustacheTemplate, then percent-encoding.
        assertEquals("ids=%5B%22a%22%2C%22b%22%5D",
                FormUrlEncodedBody.resolve("ids={{ids}}", Map.of("ids", List.of("a", "b"))));
    }

    @Test
    void resolveShouldEvaluateBooleanSectionLikeOtherBodyTypes() {
        // A Boolean must stay a Boolean: as the string "false" it would be truthy for Mustache.
        String template = "a=1{{#flag}}&b=2{{/flag}}";
        assertEquals("a=1", FormUrlEncodedBody.resolve(template, Map.of("flag", false)));
        assertEquals("a=1&b=2", FormUrlEncodedBody.resolve(template, Map.of("flag", true)));
    }

    @Test
    void resolveShouldEncodeNumberValues() {
        assertEquals("amount=12.5&qty=3",
                FormUrlEncodedBody.resolve("amount={{amount}}&qty={{qty}}",
                        Map.of("amount", 12.5, "qty", 3)));
    }

    /**
     * Every way a template can render a value must encode it: a value read from a nested object
     * (a step output, or an object tool argument) is caller data just like a top-level one.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "t={{step.amp}}                          | t=x%26y%3D1",
        "t={{step.token}}                        | t=%7B%7Bsecret%7D%7D",
        "t={{step.inner.deep}}                   | t=p+q%26r%3D1",
        "t={{{step.amp}}}                        | t=x%26y%3D1",
        "t={{&step.amp}}                         | t=x%26y%3D1",
        "{{#step}}t={{amp}}{{/step}}             | t=x%26y%3D1",
        "{{#step.items}}i={{.}}&{{/step.items}}  | i=a%26b&i=c&",
        "lit=%2F&t={{step.amp}}                  | lit=%2F&t=x%26y%3D1",
        "t={{step.missing}}                      | t=",
        "t={{step.count}}&on={{step.on}}         | t=7&on=true",
    })
    void resolveShouldEncodeEveryRenderedValue(String template, String expected) {
        assertEquals(expected, FormUrlEncodedBody.resolve(template, nestedParameters()));
    }

    @Test
    void resolveShouldEncodeWholeObjectRendering() {
        String body = FormUrlEncodedBody.resolve("t={{step}}", nestedParameters());

        assertTrue(body.startsWith("t=%7B"), body);
        assertFalse(body.substring(2).contains("&"), "No extra field may appear: " + body);
        assertFalse(body.substring(2).contains("="), "No extra field may appear: " + body);
    }

    @Test
    void resolveShouldEvaluateNestedBooleanSectionsLikeOtherBodyTypes() {
        Map<String, Object> params = nestedParameters();
        assertEquals("a=1&b=2", FormUrlEncodedBody.resolve("a=1{{#step.on}}&b=2{{/step.on}}", params));
        assertEquals("a=1", FormUrlEncodedBody.resolve("a=1{{#step.off}}&b=2{{/step.off}}", params));
        assertEquals("a=1&c=3", FormUrlEncodedBody.resolve("a=1{{^step.off}}&c=3{{/step.off}}", params));
    }

    /**
     * With values that need no encoding, the form string resolves exactly like any other body
     * type: the encoder changes how values are printed, never which sections render.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "a={{plain}}",
        "a=1{{#flag}}&b=2{{/flag}}",
        "a=1{{^flag}}&b=2{{/flag}}",
        "a=1{{#on}}&b={{plain}}{{/on}}",
        "{{#obj}}k={{key}}{{/obj}}",
        "{{#obj.list}}i={{.}}&{{/obj.list}}",
        "n={{num}}&d={{dec}}",
        "x={{obj.key}}&y={{missing}}",
    })
    void resolveShouldMatchPlainResolutionWhenValuesNeedNoEncoding(String template) {
        Map<String, Object> params = new HashMap<>();
        params.put("plain", "abc");
        params.put("flag", false);
        params.put("on", true);
        params.put("num", 42);
        params.put("dec", 1.5);
        params.put("obj", Map.of("key", "v1", "list", List.of("p", "q")));

        assertEquals(Resolver.resolveMustacheTemplate(template, params),
                FormUrlEncodedBody.resolve(template, params));
    }

    @Test
    void encodeShouldEncodeNestedValuesInFormMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("t", "{{step.amp}}");
        data.put("u", "{{step.token}}");

        assertEquals("t=x%26y%3D1&u=%7B%7Bsecret%7D%7D",
                FormUrlEncodedBody.encode(data, nestedParameters()));
    }

    private static Map<String, Object> nestedParameters() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("deep", "p q&r=1");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("amp", "x&y=1");
        step.put("token", "{{secret}}");
        step.put("inner", inner);
        step.put("items", List.of("a&b", "c"));
        step.put("count", 7);
        step.put("on", true);
        step.put("off", false);
        Map<String, Object> params = new HashMap<>();
        params.put("step", step);
        return params;
    }

    @Test
    void resolveShouldLeaveTemplateInPlaceWhenNoParametersAreGiven() {
        // The caller's unresolved-template guard reports it.
        assertEquals("q={{v}}", FormUrlEncodedBody.resolve("q={{v}}", Map.of()));
    }
}
