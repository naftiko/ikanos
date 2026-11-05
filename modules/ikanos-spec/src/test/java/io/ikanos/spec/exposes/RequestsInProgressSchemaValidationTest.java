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
package io.ikanos.spec.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * {@code maxRequestsInProgress} on exposed REST and MCP adapters (#920).
 */
class RequestsInProgressSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = RequestsInProgressSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "8", "500"})
    void schemaShouldAcceptAPositiveLimitOnRestAdapters(String limit) throws Exception {
        assertValid(rest("maxRequestsInProgress: " + limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2.5", "\"8\""})
    void schemaShouldRejectALimitThatIsNotAPositiveInteger(String limit) throws Exception {
        assertInvalid(rest("maxRequestsInProgress: " + limit));
    }

    @Test
    void schemaShouldAcceptALimitOnMcpAdapters() throws Exception {
        assertValid(mcp("maxRequestsInProgress: 16"));
    }

    @Test
    void specShouldReadTheLimitAndLeaveItUnsetByDefault() throws Exception {
        IkanosSpec limited = YAML_MAPPER.readValue(rest("maxRequestsInProgress: 8"), IkanosSpec.class);
        IkanosSpec unset = YAML_MAPPER.readValue(rest(""), IkanosSpec.class);

        assertEquals(8, limited.getCapability().getExposes().get(0).getMaxRequestsInProgress());
        assertNull(unset.getCapability().getExposes().get(0).getMaxRequestsInProgress());
    }

    private static String rest(String line) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: demo
                    %s
                    resources:
                      ping:
                        path: "/ping"
                        operations:
                          ping:
                            method: GET
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                """.formatted(IKANOS, line);
    }

    private static String mcp(String line) {
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

    private static void assertValid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertTrue(errors.isEmpty(), "Expected valid capability, got:\n" + errors + "\n" + yaml);
    }

    private static void assertInvalid(String yaml) throws Exception {
        List<Error> errors = schema.validate(YAML_MAPPER.readTree(yaml));
        assertFalse(errors.isEmpty(), "Expected schema errors for:\n" + yaml);
    }
}
