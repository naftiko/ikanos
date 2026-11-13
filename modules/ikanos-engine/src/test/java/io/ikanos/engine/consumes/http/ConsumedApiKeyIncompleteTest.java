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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #964: a consumed apikey without a {@code key} or a {@code value} can never be sent. Before, the
 * capability loaded and every call to that upstream failed in {@link ConsumedAuthentication#apply}
 * (and an MCP upstream still sent {@code tools/list}, since {@code canRender} answered true). Now
 * the capability refuses to load, naming the adapter and what is missing.
 */
class ConsumedApiKeyIncompleteTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String TARGET = "https://api.example.com/items";

    @Test
    void findUnsupportedShouldReportAnApiKeyWithoutAValue() {
        List<String> errors = ConsumedAuthentication.findUnsupported(spec(apiKey("X-Api-Key", null)));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'crm'"), errors.get(0));
        assertTrue(errors.get(0).contains("no value"), errors.get(0));
        assertFalse(errors.get(0).contains("no key"), errors.get(0));
    }

    @Test
    void findUnsupportedShouldReportAnApiKeyWithoutAKey() {
        List<String> errors = ConsumedAuthentication.findUnsupported(spec(apiKey(null, "{{KEY}}")));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'crm'"), errors.get(0));
        assertTrue(errors.get(0).contains("no key"), errors.get(0));
        assertFalse(errors.get(0).contains("no value"), errors.get(0));
    }

    @Test
    void findUnsupportedShouldReportAnApiKeyWithNeitherAKeyNorAValue() {
        List<String> errors = ConsumedAuthentication.findUnsupported(spec(apiKey(null, null)));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("no key and no value"), errors.get(0));
    }

    @Test
    void findUnsupportedShouldAcceptACompleteApiKey() {
        assertEquals(List.of(),
                ConsumedAuthentication.findUnsupported(spec(apiKey("X-Api-Key", "{{KEY}}"))));
    }

    /** The error never shows the value, even when only the key is missing. */
    @Test
    void findUnsupportedShouldNeverShowTheValue() {
        List<String> errors = ConsumedAuthentication.findUnsupported(spec(apiKey(null, "s3cr3t")));

        assertFalse(errors.get(0).contains("s3cr3t"), errors.get(0));
    }

    @Test
    void findUnsupportedShouldReportAnApiKeyInEveryConsumedAdapter() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: crm
                      type: http
                      baseUri: "https://crm.example.com"
                      authentication:
                        type: apikey
                        key: "X-Api-Key"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: GET
                    - namespace: billing
                      type: mcp
                      endpoint: "https://billing.example.com/mcp"
                      authentication:
                        type: apikey
                        value: "{{BILLING_KEY}}"
                    - namespace: erp
                      type: http
                      baseUri: "https://erp.example.com"
                      authentication:
                        type: apikey
                        key: "X-Api-Key"
                        value: "{{ERP_KEY}}"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: GET
                  exposes: []
                """.formatted(IKANOS), IkanosSpec.class);

        List<String> errors = ConsumedAuthentication.findUnsupported(spec);

        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'crm'") && errors.get(0).contains("no value"),
                errors.get(0));
        assertTrue(errors.get(1).contains("'billing'") && errors.get(1).contains("no key"),
                errors.get(1));
    }

    /** A spec built in code, with its adapter in the top-level consumes, is checked too. */
    @Test
    void findUnsupportedShouldReportAnApiKeyInATopLevelConsumes() {
        IkanosSpec spec = new IkanosSpec();
        spec.getConsumes().add(
                new HttpClientSpec("crm", "https://crm.example.com", apiKey("X-Api-Key", null)));

        List<String> errors = ConsumedAuthentication.findUnsupported(spec);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'crm'"), errors.get(0));
    }

    /** The check in apply stays, as a last guard for a spec that skipped the load-time check. */
    @Test
    void applyShouldStillRefuseAnApiKeyWithoutAValue() {
        Request request = new Request(Method.GET, TARGET);

        assertThrows(IllegalArgumentException.class, () -> ConsumedAuthentication
                .apply(apiKey("X-Api-Key", null), null, request, TARGET, Map.of()));
        assertNull(request.getHeaders().getFirstValue("X-Api-Key"));
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"key", "value"})
    void capabilityShouldRefuseToLoadAnHttpUpstreamWithAnIncompleteApiKey(String missing) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loadHttp(without(missing)));

        assertTrue(e.getMessage().contains("'crm'"), e.getMessage());
        assertTrue(e.getMessage().contains("no " + missing), e.getMessage());
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"key", "value"})
    void capabilityShouldRefuseToLoadAnMcpUpstreamWithAnIncompleteApiKey(String missing) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loadMcp(without(missing)));

        assertTrue(e.getMessage().contains("'docs'"), e.getMessage());
        assertTrue(e.getMessage().contains("no " + missing), e.getMessage());
    }

    // #1004 review: a blank key or value can't be sent either. Rendering "" gives "", since
    // there is no variable to miss, and apply would send an empty header, or one with no name.

    @ParameterizedTest(name = "key \"{0}\", value \"{1}\"")
    @CsvSource(value = {"'' | {{KEY}} | an empty key", "X-Api-Key | '' | an empty value",
            "X-Api-Key | '   ' | an empty value", "'' | '' | an empty key and an empty value",
            "'' | | an empty key and no value"}, delimiter = '|')
    void findUnsupportedShouldReportABlankKeyOrValue(String key, String value, String lacks) {
        List<String> errors = ConsumedAuthentication.findUnsupported(spec(apiKey(key, value)));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'crm'"), errors.get(0));
        assertTrue(errors.get(0).endsWith("this one has " + lacks), errors.get(0));
    }

    @ParameterizedTest(name = "blank {0}")
    @ValueSource(strings = {"key", "value"})
    void capabilityShouldRefuseToLoadAnHttpUpstreamWithABlankApiKey(String blank) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loadHttp(blankOne(blank)));

        assertTrue(e.getMessage().contains("'crm'"), e.getMessage());
        assertTrue(e.getMessage().contains("an empty " + blank), e.getMessage());
    }

    @ParameterizedTest(name = "blank {0}")
    @ValueSource(strings = {"key", "value"})
    void capabilityShouldRefuseToLoadAnMcpUpstreamWithABlankApiKey(String blank) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loadMcp(blankOne(blank)));

        assertTrue(e.getMessage().contains("'docs'"), e.getMessage());
        assertTrue(e.getMessage().contains("an empty " + blank), e.getMessage());
    }

    @ParameterizedTest(name = "key \"{0}\", value \"{1}\"")
    @CsvSource(value = {"'' | static", "' ' | static", "X-Api-Key | ''", "X-Api-Key | ' '"},
            delimiter = '|')
    void applyShouldRefuseABlankKeyOrValue(String key, String value) {
        Request request = new Request(Method.GET, TARGET);

        assertThrows(IllegalArgumentException.class, () -> ConsumedAuthentication
                .apply(apiKey(key, value), null, request, TARGET, Map.of()));
        assertTrue(request.getHeaders().isEmpty(), request.getHeaders().toString());
        assertEquals(TARGET, request.getResourceRef().toString());
    }

    private static String blankOne(String blank) {
        return "key".equals(blank) ? "key: \"\"\n        value: \"static\""
                : "key: \"X-Api-Key\"\n        value: \"\"";
    }

    @Test
    void capabilityShouldLoadAnHttpUpstreamWithACompleteApiKey() {
        assertDoesNotThrow(() -> loadHttp("key: \"X-Api-Key\"\n        value: \"static\""));
    }

    @Test
    void capabilityShouldLoadAnMcpUpstreamWithACompleteApiKey() {
        assertDoesNotThrow(() -> loadMcp("key: \"X-Api-Key\"\n        value: \"static\""));
    }

    private static String without(String missing) {
        return "key".equals(missing) ? "value: \"static\"" : "key: \"X-Api-Key\"";
    }

    private static ApiKeyAuthenticationSpec apiKey(String key, String value) {
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setType("apikey");
        apiKey.setKey(key);
        apiKey.setValue(value);
        return apiKey;
    }

    private static IkanosSpec spec(ApiKeyAuthenticationSpec apiKey) {
        IkanosSpec spec = new IkanosSpec();
        spec.getConsumes().add(new HttpClientSpec("crm", "https://crm.example.com", apiKey));
        return spec;
    }

    private static Capability loadHttp(String apiKeyFields) throws Exception {
        return new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: crm
                      type: http
                      baseUri: "https://crm.example.com"
                      authentication:
                        type: apikey
                        %s
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list:
                              method: GET
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: 0
                      namespace: demo
                      resources:
                        items:
                          path: "/items"
                          operations:
                            get:
                              method: GET
                              call: crm.list
                """.formatted(IKANOS, apiKeyFields), IkanosSpec.class));
    }

    private static Capability loadMcp(String apiKeyFields) throws Exception {
        return new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: docs
                      type: mcp
                      endpoint: "https://docs.example.com/mcp"
                      authentication:
                        type: apikey
                        %s
                      tools:
                        search:
                          description: "Search the docs."
                  exposes:
                    - type: rest
                      address: "localhost"
                      port: 0
                      namespace: demo
                      resources:
                        search:
                          path: "/search"
                          operations:
                            get:
                              method: GET
                              call: docs.search
                """.formatted(IKANOS, apiKeyFields), IkanosSpec.class));
    }
}
