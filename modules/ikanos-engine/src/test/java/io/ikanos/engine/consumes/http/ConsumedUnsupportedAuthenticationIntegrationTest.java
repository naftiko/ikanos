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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #842, end to end: a capability whose consumed adapter declares an authentication type the
 * engine can't send refuses to load, naming the adapter and the type, instead of starting and
 * calling its upstream with no credential.
 */
class ConsumedUnsupportedAuthenticationIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void capabilityShouldRefuseToLoadWithAnOAuth2ConsumedCredential() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("type: oauth2\n        authorizationServerUri: \"https://auth.example.com\""));

        assertTrue(e.getMessage().contains("crm"), e.getMessage());
        assertTrue(e.getMessage().contains("'oauth2'"), e.getMessage());
    }

    @Test
    void capabilityShouldLoadWithASupportedConsumedCredential() {
        assertDoesNotThrow(() -> load("type: bearer\n        token: \"static\""));
    }

    private static Capability load(String authentication) throws Exception {
        return new Capability(IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: crm
                      type: http
                      baseUri: "https://crm.example.com"
                      authentication:
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
                """.formatted(IKANOS, authentication), IkanosSpec.class));
    }
}
