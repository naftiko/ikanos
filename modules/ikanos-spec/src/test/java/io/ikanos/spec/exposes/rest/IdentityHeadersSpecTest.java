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
package io.ikanos.spec.exposes.rest;

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

/** {@code forward.identityHeaders} (#998): model and schema. */
class IdentityHeadersSpecTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = IdentityHeadersSpecTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @Test
    void specShouldReadTheShortAndTheObjectForms() throws Exception {
        RestServerForwardSpec forward = forwardOf("""
                X-User-Id: sub
                X-User-Email:
                  claim: email
                  ifMissing: omit
                """);

        IdentityHeaderSpec userId = forward.getIdentityHeaders().get("X-User-Id");
        IdentityHeaderSpec email = forward.getIdentityHeaders().get("X-User-Email");
        assertEquals("sub", userId.getClaim());
        assertNull(userId.getIfMissing());
        assertFalse(userId.omitsWhenMissing());
        assertEquals("email", email.getClaim());
        assertTrue(email.omitsWhenMissing());
    }

    @Test
    void specShouldKeepTheDeclarationOrder() throws Exception {
        RestServerForwardSpec forward = forwardOf("""
                X-B: sub
                X-A: email
                """);

        assertEquals(List.of("X-B", "X-A"), List.copyOf(forward.getIdentityHeaders().keySet()));
    }

    @Test
    void schemaShouldAcceptTheShortAndTheObjectForms() throws Exception {
        assertValid(capability("""
                X-User-Id: sub
                X-User-Roles: realm_access.roles
                X-User-Email:
                  claim: email
                  ifMissing: omit
                """));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "X-User-Id: \"\"\n",
        "X-User-Id:\n  ifMissing: omit\n",
        "X-User-Id:\n  claim: sub\n  ifMissing: ignore\n",
        "X-User-Id:\n  claim: sub\n  header: X-Other\n",
        "\"X User\": sub\n"
    })
    void schemaShouldRejectAnIncompleteOrUnknownEntry(String identityHeaders) throws Exception {
        assertInvalid(capability(identityHeaders));
    }

    private static RestServerForwardSpec forwardOf(String identityHeaders) throws Exception {
        IkanosSpec spec = YAML_MAPPER.readValue(capability(identityHeaders), IkanosSpec.class);
        RestServerSpec adapter = (RestServerSpec) spec.getCapability().getExposes().get(0);
        return adapter.getResources().get("files").getForward();
    }

    private static String capability(String identityHeaders) {
        return """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 8080
                    namespace: app
                    authentication:
                      type: oauth2
                      authorizationServerUri: "https://idp.example.org/realms/app"
                      resource: "https://app.example.org/api"
                    resources:
                      files:
                        path: "/files/{{path}}"
                        forward:
                          targetNamespace: files
                          identityHeaders:
                %s
                """.formatted(IKANOS, identityHeaders.indent(12));
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
