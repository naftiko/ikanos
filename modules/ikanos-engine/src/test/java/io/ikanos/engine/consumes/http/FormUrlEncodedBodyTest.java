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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

    @Test
    void resolveShouldLeaveTemplateInPlaceWhenNoParametersAreGiven() {
        // The caller's unresolved-template guard reports it.
        assertEquals("q={{v}}", FormUrlEncodedBody.resolve("q={{v}}", Map.of()));
    }
}
