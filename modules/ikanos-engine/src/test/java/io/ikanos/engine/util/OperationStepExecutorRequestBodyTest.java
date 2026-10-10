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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpInvocation;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for structured request-body encoding in {@link OperationStepExecutor}: the payload
 * bytes, not just the media type.
 */
class OperationStepExecutorRequestBodyTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
