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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * MCP cache hints ({@code ttlMs}, {@code cacheScope}) in the Ikanos schema (MCP 2026-07-28,
 * SEP-2549).
 */
class CacheHintsSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = CacheHintsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "3600000", "2147483647"})
    void schemaShouldAcceptAServerTtlOfZeroOrMore(String ttl) throws Exception {
        assertValid(mcp("ttlMs: " + ttl));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2.5", "\"60000\"", "2147483648"})
    void schemaShouldRejectAServerTtlThatIsNotAnIntegerOfZeroOrMore(String ttl) throws Exception {
        assertInvalid(mcp("ttlMs: " + ttl));
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "private", "auto"})
    void schemaShouldAcceptTheSpecCacheScopes(String scope) throws Exception {
        assertValid(mcp("cacheScope: " + scope));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared", "PUBLIC", "\"\""})
    void schemaShouldRejectAnUnknownCacheScope(String scope) throws Exception {
        assertInvalid(mcp("cacheScope: " + scope));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: 0", "ttlMs: 60000", "cacheScope: public", "cacheScope: private", "cacheScope: auto",
            "ttlFromUpstream: true", "ttlFromUpstream: false"})
    void schemaShouldAcceptCacheHintsOnAResource(String line) throws Exception {
        assertValid(mcpResource(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ttlMs: -1", "ttlMs: \"60000\"", "cacheScope: shared",
            "ttlFromUpstream: \"yes\""})
    void schemaShouldRejectInvalidCacheHintsOnAResource(String line) throws Exception {
        assertInvalid(mcpResource(line));
    }

    static String mcpResource(String line) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 8080
                    namespace: demo
                    description: "Demo"
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      guide:
                        display: "Guide"
                        uri: "docs://guide"
                        description: "Static guide"
                        location: "file:///tmp/docs"
                        %s
                """.formatted(IKANOS, line);
    }

    static String mcp(String line) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 8080
                    namespace: demo
                    description: "Demo"
                    %s
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                """.formatted(IKANOS, line);
    }

    static void assertValid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertTrue(errors.isEmpty(), "Expected valid capability, got:\n" + errors + "\n" + yaml);
    }

    static void assertInvalid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertFalse(errors.isEmpty(), "Expected schema errors for:\n" + yaml);
    }
}
