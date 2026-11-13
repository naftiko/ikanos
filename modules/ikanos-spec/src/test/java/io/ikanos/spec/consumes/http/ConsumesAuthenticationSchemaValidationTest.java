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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * #842: a consumed adapter only accepts the authentication types the engine can send. Before,
 * `ConsumesHttp` and `ConsumesMcp` shared the exposed adapters' `Authentication`, which also
 * lists `oauth2`, so `ikanos validate` passed a file the engine then refused to load.
 */
class ConsumesAuthenticationSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String OAUTH2 = """
            type: "oauth2"
                    authorizationServerUri: "https://auth.example.com"
                    resource: "https://api.example.com\"""";
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ConsumesAuthenticationSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    @ParameterizedTest(name = "consumes type {0}")
    @ValueSource(strings = {"http", "mcp"})
    void schemaShouldRejectOAuth2OnAConsumedAdapter(String type) throws Exception {
        assertFalse(validate(consumed(type, OAUTH2), "").isEmpty(),
                "oauth2 under consumes type " + type + " should be rejected");
    }

    @ParameterizedTest(name = "consumes type {0}")
    @ValueSource(strings = {"http", "mcp"})
    void schemaShouldAcceptABearerTokenOnAConsumedAdapter(String type) throws Exception {
        List<Error> errors = validate(consumed(type, """
                type: "bearer"
                        token: "{{TOKEN}}\""""), "");

        assertEquals(List.of(), errors, errors.toString());
    }

    /** #964: an apikey that can't be sent is refused by validate, before the engine refuses it. */
    @ParameterizedTest(name = "consumes type {0}")
    @ValueSource(strings = {"http", "mcp"})
    void schemaShouldRejectAnApiKeyWithoutAValueOnAConsumedAdapter(String type) throws Exception {
        List<Error> errors = validate(consumed(type, """
                type: "apikey"
                        key: "X-Api-Key\""""), "");

        assertTrue(errors.stream()
                .anyMatch(e -> e.toString().contains("required property 'value'")),
                errors.toString());
    }

    @ParameterizedTest(name = "consumes type {0}")
    @ValueSource(strings = {"http", "mcp"})
    void schemaShouldRejectAnApiKeyWithoutAKeyOnAConsumedAdapter(String type) throws Exception {
        List<Error> errors = validate(consumed(type, """
                type: "apikey"
                        value: "{{API_KEY}}\""""), "");

        assertTrue(errors.stream()
                .anyMatch(e -> e.toString().contains("required property 'key'")),
                errors.toString());
    }

    /** #1004 review: an empty key or value can't be sent either. */
    @ParameterizedTest(name = "consumes type {0}, empty {1}")
    @CsvSource({"http,key", "http,value", "mcp,key", "mcp,value"})
    void schemaShouldRejectAnEmptyApiKeyOnAConsumedAdapter(String type, String empty)
            throws Exception {
        List<Error> errors = validate(consumed(type, """
                type: "apikey"
                        %s""".formatted(emptyOne(empty))), "");

        assertTrue(errors.stream().anyMatch(e -> e.toString().contains(
                "/authentication/" + empty + ": must be at least 1 characters long")),
                errors.toString());
    }

    @ParameterizedTest(name = "empty {0}")
    @ValueSource(strings = {"key", "value"})
    void schemaShouldRejectAnEmptyApiKeyOnAnExposedAdapter(String empty) throws Exception {
        List<Error> errors = validate(consumed("http", """
                type: "bearer"
                        token: "{{TOKEN}}\""""), """
                      authentication:
                        type: "apikey"
                        %s
                """.formatted(emptyOne(empty)));

        assertTrue(errors.stream().anyMatch(e -> e.toString().contains(
                "/authentication/" + empty + ": must be at least 1 characters long")),
                errors.toString());
    }

    private static String emptyOne(String empty) {
        return "key".equals(empty) ? "key: \"\"\n        value: \"{{API_KEY}}\""
                : "key: \"X-Api-Key\"\n        value: \"\"";
    }

    @ParameterizedTest(name = "consumes type {0}")
    @ValueSource(strings = {"http", "mcp"})
    void schemaShouldAcceptACompleteApiKeyOnAConsumedAdapter(String type) throws Exception {
        List<Error> errors = validate(consumed(type, """
                type: "apikey"
                        key: "X-Api-Key"
                        value: "{{API_KEY}}"
                        placement: "query\""""), "");

        assertEquals(List.of(), errors, errors.toString());
    }

    /** Exposed adapters share AuthApiKey, and the engine refuses an incomplete one there (#856). */
    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"key", "value"})
    void schemaShouldRejectAnIncompleteApiKeyOnAnExposedAdapter(String missing) throws Exception {
        String field = "key".equals(missing) ? "value: \"{{API_KEY}}\"" : "key: \"X-Api-Key\"";
        List<Error> errors = validate(consumed("http", """
                type: "bearer"
                        token: "{{TOKEN}}\""""), """
                      authentication:
                        type: "apikey"
                        %s
                """.formatted(field));

        assertFalse(errors.isEmpty(), "an exposed apikey without a " + missing
                + " should be rejected");
    }

    @Test
    void schemaShouldAcceptACompleteApiKeyOnAnExposedAdapter() throws Exception {
        List<Error> errors = validate(consumed("http", """
                type: "bearer"
                        token: "{{TOKEN}}\""""), """
                      authentication:
                        type: "apikey"
                        key: "X-Api-Key"
                        value: "{{API_KEY}}"
                """);

        assertEquals(List.of(), errors, errors.toString());
    }

    @Test
    void schemaShouldStillAcceptOAuth2OnAnExposedAdapter() throws Exception {
        List<Error> errors = validate(consumed("http", """
                type: "bearer"
                        token: "{{TOKEN}}\""""), """
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "https://auth.example.com"
                        resource: "https://api.example.com"
                """);

        assertEquals(List.of(), errors, errors.toString());
    }

    private static String consumed(String type, String authentication) {
        return "http".equals(type) ? """
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.com"
                      authentication:
                        %s
                      resources:
                        items:
                          path: "/items"
                          operations:
                            list-items:
                              method: "GET"
                """.formatted(authentication) : """
                    - type: "mcp"
                      namespace: "api"
                      endpoint: "https://api.example.com/mcp"
                      authentication:
                        %s
                      tools:
                        list-items:
                          description: "List items."
                """.formatted(authentication);
    }

    private static List<Error> validate(String consumes, String exposedAuthentication)
            throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Consumed authentication"
                  description: "Consumed authentication types test"
                capability:
                  consumes:
                %s
                  exposes:
                    - type: "mcp"
                      namespace: "api-mcp"
                      port: 3001
                %s
                      tools:
                        list-items:
                          description: "List items."
                          call: "api.list-items"
                """.formatted(VersionHelper.getSchemaVersion(), consumes.stripTrailing(),
                exposedAuthentication.stripTrailing());
        return schema.validate(YAML.readTree(yaml)).stream().toList();
    }
}
