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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #842: an authentication type that {@link ConsumedAuthentication} can't send (the schema allows
 * {@code oauth2} on consumes, and code can build a {@code Bearer} with a capital) is refused,
 * instead of sending the upstream call with no credential and no error.
 */
class ConsumedAuthenticationUnsupportedTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final String TARGET = "https://api.example.com/items";

    @Test
    void applyShouldRefuseAnOAuth2Credential() {
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setType("oauth2");
        oauth2.setAuthorizationServerUri("https://auth.example.com");
        Request request = new Request(Method.GET, TARGET);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(oauth2, null, request, TARGET, Map.of()));

        assertTrue(e.getMessage().contains("'oauth2'"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    @Test
    void applyShouldRefuseATypeWrittenWithAnotherCase() {
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setType("Bearer");
        bearer.setToken("s3cr3t");
        Request request = new Request(Method.GET, TARGET);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(bearer, null, request, TARGET, Map.of()));

        assertTrue(e.getMessage().contains("'Bearer'"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    /** A spec built in code may have no type: a clear error, not a NullPointerException. */
    @Test
    void applyShouldRefuseACredentialWithoutAType() {
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setType(null);
        bearer.setToken("s3cr3t");
        Request request = new Request(Method.GET, TARGET);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(bearer, null, request, TARGET, Map.of()));

        assertTrue(e.getMessage().contains("'null'"), e.getMessage());
    }

    @Test
    void findUnsupportedShouldReportACredentialWithoutAType() {
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setType(null);
        IkanosSpec spec = new IkanosSpec();
        spec.getConsumes().add(new HttpClientSpec("crm", "https://crm.example.com", bearer));

        List<String> errors = ConsumedAuthentication.findUnsupported(spec);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("crm") && errors.get(0).contains("'null'"),
                errors.get(0));
    }

    @Test
    void findUnsupportedShouldNameEveryConsumedAdapterWithAnUnsupportedType() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - namespace: crm
                      type: http
                      baseUri: "https://crm.example.com"
                      authentication:
                        type: oauth2
                        authorizationServerUri: "https://auth.example.com"
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
                        type: oauth2
                        authorizationServerUri: "https://auth.example.com"
                    - namespace: erp
                      type: http
                      baseUri: "https://erp.example.com"
                      authentication:
                        type: bearer
                        token: "static"
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
        assertTrue(errors.get(0).contains("crm") && errors.get(0).contains("'oauth2'"),
                errors.get(0));
        assertTrue(errors.get(1).contains("billing"), errors.get(1));
    }

    @Test
    void findUnsupportedShouldAcceptASpecWithoutConsumedAuthentication() {
        assertEquals(List.of(), ConsumedAuthentication.findUnsupported(null));
        assertEquals(List.of(), ConsumedAuthentication.findUnsupported(new IkanosSpec()));
    }
}
