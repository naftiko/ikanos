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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * The {@code awsSigV4} authentication (#954): accepted on consumed adapters only, and read into
 * {@link AwsSigV4AuthenticationSpec}.
 */
class AwsSigV4AuthenticationSchemaTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = AwsSigV4AuthenticationSchemaTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    private static final String AWS_AUTH = """
                  type: awsSigV4
                  accessKeyId: "{{S3_ACCESS_KEY_ID}}"
                  secretAccessKey: "{{S3_SECRET_ACCESS_KEY}}"
                  region: us-east-1
            """;

    private static String capability(String consumedAuth, String exposedAuth) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: store
                    type: http
                    baseUri: "https://minio.example/documents"
                    authentication:
                %s
                    resources:
                      object:
                        path: "/{{key}}"
                        operations:
                          get-object:
                            method: GET
                            inputParameters:
                              key:
                                in: path
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: files
                %s
                    resources:
                      files:
                        path: "/files/{{path}}"
                        forward:
                          targetNamespace: store
                          trustedHeaders:
                          - Range
                """.formatted(VersionHelper.getSchemaVersion(), consumedAuth,
                exposedAuth == null ? "" : "    authentication:\n" + exposedAuth);
    }

    private static List<Error> validate(String yaml) throws Exception {
        return schema.validate(IkanosMappers.yaml().readTree(yaml));
    }

    @Test
    void schemaShouldAcceptAwsSigV4WhenOnAConsumedHttpAdapter() throws Exception {
        List<Error> errors = validate(capability(AWS_AUTH, null));
        assertTrue(errors.isEmpty(), "Expected no errors, got:\n" + errors);
    }

    /** A consumed MCP adapter, which sends its JSON-RPC POSTs through the same signing. */
    private static String mcpCapability(String consumedAuth) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: billing
                    type: mcp
                    endpoint: "https://billing.example.execute-api.us-east-1.amazonaws.com/mcp"
                    authentication:
                %s
                    tools:
                      get-invoice:
                        description: Retrieve one invoice
                  exposes:
                  - type: mcp
                    port: 8080
                    namespace: billing-mcp
                    tools:
                      get-invoice:
                        description: Retrieve one invoice
                        call: billing.get-invoice
                """.formatted(VersionHelper.getSchemaVersion(), consumedAuth);
    }

    @Test
    void schemaShouldAcceptAwsSigV4WhenOnAConsumedMcpAdapter() throws Exception {
        List<Error> errors = validate(mcpCapability(AWS_AUTH + """
                  service: execute-api
            """));
        assertTrue(errors.isEmpty(), "Expected no errors, got:\n" + errors);
    }

    @Test
    void deserializationShouldBuildTheSpecWhenOnAConsumedMcpAdapter() throws Exception {
        IkanosSpec spec = IkanosMappers.yaml().readValue(mcpCapability(AWS_AUTH), IkanosSpec.class);

        McpClientSpec billing = (McpClientSpec) spec.getCapability().getConsumes().get(0);
        AwsSigV4AuthenticationSpec auth =
                assertInstanceOf(AwsSigV4AuthenticationSpec.class, billing.getAuthentication());
        assertEquals("us-east-1", auth.getRegion());
    }

    @Test
    void schemaShouldAcceptTheOptionalFieldsWhenDeclared() throws Exception {
        List<Error> errors = validate(capability(AWS_AUTH + """
                  service: execute-api
                  sessionToken: "{{S3_SESSION_TOKEN}}"
                  unsignedPayload: true
            """, null));
        assertTrue(errors.isEmpty(), "Expected no errors, got:\n" + errors);
    }

    @Test
    void schemaShouldRejectAwsSigV4WhenTheRegionIsMissing() throws Exception {
        assertFalse(validate(capability(AWS_AUTH.replace("      region: us-east-1\n", ""), null))
                .isEmpty());
    }

    @Test
    void schemaShouldRejectAwsSigV4WhenOnAnExposedAdapter() throws Exception {
        assertFalse(validate(capability(AWS_AUTH, AWS_AUTH)).isEmpty(),
                "awsSigV4 only signs outgoing requests; an exposed adapter can't declare it");
    }

    @Test
    void deserializationShouldBuildTheSpecWhenOnAConsumedHttpAdapter() throws Exception {
        IkanosSpec spec = IkanosMappers.yaml().readValue(capability(AWS_AUTH + """
                  sessionToken: "{{S3_SESSION_TOKEN}}"
            """, null), IkanosSpec.class);

        HttpClientSpec store = (HttpClientSpec) spec.getCapability().getConsumes().get(0);
        AwsSigV4AuthenticationSpec auth =
                assertInstanceOf(AwsSigV4AuthenticationSpec.class, store.getAuthentication());
        assertEquals("awsSigV4", auth.getType());
        assertEquals("{{S3_ACCESS_KEY_ID}}", auth.getAccessKeyId());
        assertArrayEquals("{{S3_SECRET_ACCESS_KEY}}".toCharArray(), auth.getSecretAccessKey());
        assertEquals("us-east-1", auth.getRegion());
        assertEquals("{{S3_SESSION_TOKEN}}", auth.getSessionToken());
        assertNull(auth.getService());
        assertNull(auth.getUnsignedPayload());
    }
}
