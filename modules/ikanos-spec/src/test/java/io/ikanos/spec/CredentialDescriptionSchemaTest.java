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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;

/**
 * #845: the schema documented `$env.NAME` expressions for credentials, but no code reads them, so
 * such a value was sent as literal text. Credentials take secrets from `binds` through `{{VAR}}`.
 */
class CredentialDescriptionSchemaTest {

    private static JsonNode schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = CredentialDescriptionSchemaTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = IkanosMappers.json().readTree(in);
        }
    }

    @Test
    void noDescriptionShouldDocumentEnvExpressions() {
        List<String> found = new ArrayList<>();
        collectEnvMentions(schema, "", found);

        assertEquals(List.of(), found, "$env.NAME is not an expression the engine resolves");
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({
            "AuthBasic, username, API_USER",
            "AuthBasic, password, API_PASSWORD",
            "AuthApiKey, value, NOTION_TOKEN",
            "AuthBearer, token, GITHUB_TOKEN",
            "AuthDigest, username, API_USER",
            "AuthDigest, password, API_PASSWORD"})
    void credentialShouldDocumentBindsExpressions(String def, String field, String variable) {
        String description = schema.path("$defs").path(def).path("properties").path(field)
                .path("description").asString();

        assertTrue(description.contains("`{{" + variable + "}}`"), description);
        assertTrue(description.contains("`binds`"), description);
    }

    @Test
    void bindingWithoutLocationShouldSayItReadsEnvironmentVariables() {
        String description = schema.path("$defs").path("Binding").path("properties")
                .path("location").path("description").asString();

        assertTrue(description.contains("environment variable"), description);
    }

    private static void collectEnvMentions(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                JsonNode value = entry.getValue();
                String child = path + "/" + entry.getKey();
                if (value.isString() && value.asString().contains("$env.")) {
                    found.add(child);
                }
                collectEnvMentions(value, child, found);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectEnvMentions(node.get(i), path + "/" + i, found);
            }
        }
    }
}
