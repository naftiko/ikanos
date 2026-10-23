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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpInvocation;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for structured request-body encoding in {@link OperationStepExecutor}: the payload
 * bytes, not just the media type.
 */
class OperationStepExecutorRequestBodyTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private String schemaVersion;

    @BeforeEach
    void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    void findClientRequestForShouldUrlEncodeFormBodyMapWithBracketedKeys() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data:
                                    mode: "payment"
                                    "line_items[0][price_data][unit_amount]": "{{amount}}"
                                    "line_items[0][price_data][product_data][name]": "{{name}}"
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("amount", "500000", "name", "Shaft & seal"));

        assertEquals("mode=payment"
                + "&line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=500000"
                + "&line_items%5B0%5D%5Bprice_data%5D%5Bproduct_data%5D%5Bname%5D=Shaft+%26+seal",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldKeepFormMediaTypeForMapData() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data:
                                    q: "{{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "ok"));

        assertEquals(MediaType.APPLICATION_WWW_FORM, ctx.getRequest().getEntity().getMediaType());
    }

    @Test
    void findClientRequestForShouldSendPreEncodedFormStringAsIs() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "grant_type=client_credentials&scope={{scope}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("scope", "read"));

        assertEquals("grant_type=client_credentials&scope=read",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldUrlEncodeSubstitutedValuesInFormString() throws Exception {
        // The literal part is already encoded by the author and must not be encoded twice; only
        // the substituted value is encoded, so a caller cannot add a form field with '&'.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "grant_type=client_credentials&redirect_uri=https%3A%2F%2Fapp.example.com&scope={{scope}}"
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("scope", "read&client_id=attacker"));

        assertEquals("grant_type=client_credentials&redirect_uri=https%3A%2F%2Fapp.example.com"
                + "&scope=read%26client_id%3Dattacker", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldPreservePlusAndPercentInFormStringValues() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "q={{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "a+b 100%"));

        assertEquals("q=a%2Bb+100%25", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldJsonSerializeThenUrlEncodeListValuesInFormString()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "ids={{ids}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("ids", List.of("a", "b")));

        assertEquals("ids=%5B%22a%22%2C%22b%22%5D", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldSendNullValueAsEmptyInFormString() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "q={{v}}&r=1"
                """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("v", null);
        parameters.put("other", "x");

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", parameters);

        assertEquals("q=&r=1", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldNotUrlEncodeSubstitutedValuesInTextBody() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "hello {{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "a&b=c"));

        assertEquals("hello a&b=c", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldSendTextBodyStringUnquoted() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "hello {{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "world"));

        assertEquals("hello world", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldStillSerializeJsonObjectData() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data:
                                    name: "{{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "ok"));

        assertEquals("{\"name\":\"ok\"}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldSendJsonStringTemplateUnquoted() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"name": "{{v}}"}'
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "ok"));

        assertEquals("{\"name\": \"ok\"}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldKeepInjectedFieldsInsideJsonStringTemplateValue()
            throws Exception {
        // GHSA-6359-rp48-m9j6: a caller value must stay one JSON string, not add sibling members.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"name":"{{v}}"}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("v", "x\",\"admin\":true,\"y\":\""));

        assertEquals("{\"name\":\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\"}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldEscapeControlCharactersInJsonStringTemplateValue()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"name":"{{v}}"}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("v", "a\\b\nc\td"));

        assertEquals("{\"name\":\"a\\\\b\\nc\\td\"}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldEscapeValueInJsonObjectData() throws Exception {
        // The structured form is serialized first, then rendered, so it needs the same escaping.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data:
                                    name: "{{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("v", "x\",\"admin\":true,\"y\":\""));

        assertEquals("{\"name\":\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\"}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldRenderObjectValueAsJsonInJsonStringTemplate()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"properties":{{props}}}'
                """);
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("tokens", 5);
        props.put("model", "a\"b");

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("props", props));

        assertEquals("{\"properties\":{\"tokens\":5,\"model\":\"a\\\"b\"}}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldKeepNumberAndBooleanValuesRawInJsonStringTemplate()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"n":{{n}},"b":{{b}}}'
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("n", 42, "b", true));

        assertEquals("{\"n\":42,\"b\":true}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldKeepListValueAsJsonArrayInJsonStringTemplate()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"ids":{{ids}}}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("ids", List.of("a\"b", "c")));

        assertEquals("{\"ids\":[\"a\\\"b\",\"c\"]}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldRenderListSectionOnceInJsonStringTemplate() throws Exception {
        // A section over a list is an "if present" guard; it must not repeat per element.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"a":1{{#ids}},"ids":{{ids}}{{/ids}}}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("ids", List.of("x", "y")));

        assertEquals("{\"a\":1,\"ids\":[\"x\",\"y\"]}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldEscapeValueInLegacyStringBody() throws Exception {
        // A plain string body is sent as application/json, so it gets the same protection.
        OperationStepExecutor executor = executorWithBody("""
                                body: '{"name":"{{v}}"}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("v", "x\",\"admin\":true,\"y\":\""));

        assertEquals("{\"name\":\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\"}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldEscapeStepOutputFieldInJsonStringTemplate() throws Exception {
        // Values read from an upstream response (step outputs) are not trusted either.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"note":"{{profile.note}}"}'
                """);
        Map<String, Object> profile = Map.of("note", "n\",\"admin\":true,\"z\":\"");

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("profile", profile));

        assertEquals("{\"note\":\"n\\\",\\\"admin\\\":true,\\\"z\\\":\\\"\"}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldNotJsonEscapeValueInTextBody() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "say {{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "\"hi\""));

        assertEquals("say \"hi\"", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldRejectUnresolvedStringBodyTemplateWhenNoParametersAreGiven()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "hello {{v}}"
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "op", Map.of()));

        assertTrue(error.getMessage().contains("Unresolved template parameters in body"));
    }

    @Test
    void findClientRequestForShouldSendMissingStringTemplateVariableAsEmptyWhenOtherParametersAreGiven()
            throws Exception {
        // Pins current behavior: with any parameter present, Resolver renders a missing variable
        // as an empty string (JMustache defaultValue), so the unresolved-template guard cannot
        // catch it. Update this test if that resolution behavior changes.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "hello {{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("other", "x"));

        assertEquals("hello ", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldEncodeBracedValueInFormMapAndFormStringAlike()
            throws Exception {
        // Both variants of formUrlEncoded treat a substituted value as data: one containing
        // "{{...}}" is encoded and sent, never rejected.
        OperationStepExecutor mapExecutor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data:
                                    q: "{{v}}"
                """);
        OperationStepExecutor stringExecutor = executorWithBody("""
                                body:
                                  type: "formUrlEncoded"
                                  data: "q={{v}}"
                """);
        Map<String, Object> parameters = Map.of("v", "{{x}}");

        String fromMap = ((HttpInvocation) mapExecutor.findClientRequestFor("svc", "op", parameters))
                .getRequest().getEntity().getText();
        String fromString = ((HttpInvocation) stringExecutor.findClientRequestFor("svc", "op", parameters))
                .getRequest().getEntity().getText();

        assertEquals("q=%7B%7Bx%7D%7D", fromMap);
        assertEquals(fromMap, fromString);
    }

    @Test
    void findClientRequestForShouldKeepLiteralBraceBeforeOptionalMemberSection() throws Exception {
        // #848: the Lago update-invoice body, where the object opens right before an optional
        // member, used to fail with "Invalid triple-mustache tag".
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: '{"invoice":{{{#status}}"payment_status":"{{status}}"{{/status}}}}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("status", "succeeded"));

        assertEquals("{\"invoice\":{\"payment_status\":\"succeeded\"}}",
                ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldSendCallerTextContainingBracesInJsonBody() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"text":"{{text}}"}'
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("text", "Use {{name}} in the template"));

        assertEquals("{\"text\":\"Use {{name}} in the template\"}",
                ctx.getRequest().getEntity().getText(),
                "#858: braces in a caller value are data, not an unresolved template");
    }

    @Test
    void findClientRequestForShouldSendCallerTextContainingBracesInTextBody() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "Note: {{text}}"
                """);

        HttpInvocation ctx = (HttpInvocation) executor.findClientRequestFor("svc", "op",
                Map.of("text", "{{#x}}a{{/x}} {{{y}}}"));

        assertEquals("Note: {{#x}}a{{/x}} {{{y}}}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldNotEchoRenderedBodyWhenTemplateIsUnresolved() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "text"
                                  data: "secret-literal {{v}}"
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "op", Map.of()));

        assertFalse(error.getMessage().contains("secret-literal"),
                "#858: the error must name the missing variables, not echo the body: "
                        + error.getMessage());
        assertTrue(error.getMessage().contains("[v]"), error.getMessage());
    }

    @Test
    void capabilityShouldFailToLoadWhenBodyTemplateIsMalformed() {
        // #849 supersedes #858 here: a malformed template is rejected at load time, so it is
        // never sent, with or without parameters.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executorWithBody("""
                                body:
                                  type: "text"
                                  data: "{{#a}}unclosed"
                """));

        assertTrue(error.getMessage().contains("svc.op body"), error.getMessage());
    }

    @Test
    void findClientRequestForShouldDropAbsentSectionInJsonTemplateWhenNoParametersAreGiven()
            throws Exception {
        // #847 applies to JSON bodies too: an optional part over an absent key disappears.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"a":1{{#note}},"note":"{{note}}"{{/note}}}'
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of());

        assertEquals("{\"a\":1}", ctx.getRequest().getEntity().getText());
    }

    @Test
    void findClientRequestForShouldRejectUnresolvedJsonTemplateWhenNoParametersAreGiven()
            throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"name":"{{v}}"}'
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "op", Map.of()));

        assertTrue(error.getMessage().contains("Unresolved template parameters in body"));
    }

    @Test
    void findClientRequestForShouldRejectInvalidRenderedJsonBody() throws Exception {
        // An unquoted string placeholder cannot add a member, but it can break the document:
        // fail before sending instead of letting the upstream reject it.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"name":{{v}}}'
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "op", Map.of("v", "not json")));

        assertTrue(error.getMessage().contains("Rendered JSON body is not valid JSON"));
        assertTrue(!error.getMessage().contains("not json"),
                "the rendered body must not be echoed in the error");
    }

    @Test
    void findClientRequestForShouldRejectRenderedJsonBodyWithTrailingContent() throws Exception {
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "json"
                                  data: '{"n":{{v}}}'
                """);

        assertThrows(IllegalArgumentException.class,
                () -> executor.findClientRequestFor("svc", "op", Map.of("v", "1} {\"x\":2")));
    }

    @Test
    void findClientRequestForShouldJsonEscapeValueInStructuredDataOfNonJsonType()
            throws Exception {
        // Structured data is serialized as JSON whatever the declared type, so its values are
        // JSON-encoded even when the body is declared as xml or text.
        OperationStepExecutor executor = executorWithBody("""
                                body:
                                  type: "xml"
                                  data:
                                    name: "{{v}}"
                """);

        HttpInvocation ctx = (HttpInvocation)
                executor.findClientRequestFor("svc", "op", Map.of("v", "x\",\"admin\":true"));

        assertEquals("{\"name\":\"x\\\",\\\"admin\\\":true\"}",
                ctx.getRequest().getEntity().getText());
    }

    private OperationStepExecutor executorWithBody(String bodyYaml) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        - path: "/x"
                          operations:
                            - method: "GET"
                              name: "x"
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        - path: "/items"
                          name: "items"
                          operations:
                            - method: "POST"
                              name: "op"
                """.formatted(schemaVersion) + bodyYaml.indent(-2);
        IkanosSpec spec = YAML.readValue(yaml, IkanosSpec.class);
        return new OperationStepExecutor(new Capability(spec));
    }
}
