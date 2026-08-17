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

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class AuthenticationTest {

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void bearerAuthenticationShouldSetAuthorizationHeader() throws Exception {
        // Given
        Capability capability = getCapability();
        BearerAuthenticationSpec authentication = new BearerAuthenticationSpec();
        authentication.setType("bearer");
        authentication.setToken("{{notion_token}}");
        HttpClientSpec spec = new HttpClientSpec("notion", "https://api.notion.com/v1", authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(capability, spec);
        Request clientRequest = new Request(Method.GET, "https://api.notion.com/v1/pages");

        // When
        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        // Then
        assertThat(clientRequest.getChallengeResponse().getRawValue()).isEqualTo("notion-token");
    }

    @Test
    public void basicAuthenticationWithNoPasswordShouldSendEmptyPasswordNotThrow() throws Exception {
        // Given
        Capability capability = getCapability();
        BasicAuthenticationSpec authentication = new BasicAuthenticationSpec();
        authentication.setType("basic");
        authentication.setUsername("sk_test_FAKEKEY123");
        // password intentionally left unset (null) - schema allows this
        HttpClientSpec spec = new HttpClientSpec("stripe", "https://api.stripe.com/v1", authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(capability, spec);
        Request clientRequest = new Request(Method.GET, "https://api.stripe.com/v1/charges");

        // When
        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        // Then
        String expected = Base64.getEncoder().encodeToString("sk_test_FAKEKEY123:".getBytes());
        assertThat(clientRequest.getChallengeResponse().getIdentifier()).isEqualTo("sk_test_FAKEKEY123");
        assertThat(String.valueOf(clientRequest.getChallengeResponse().getSecret())).isEqualTo("");
        assertThat(Base64.getEncoder().encodeToString(
                (clientRequest.getChallengeResponse().getIdentifier() + ":"
                        + String.valueOf(clientRequest.getChallengeResponse().getSecret())).getBytes()))
                .isEqualTo(expected);
    }

    @Test
    public void digestAuthenticationWithNoPasswordShouldNotThrow() throws Exception {
        // Given
        Capability capability = getCapability();
        DigestAuthenticationSpec authentication = new DigestAuthenticationSpec();
        authentication.setType("digest");
        authentication.setUsername("sk_test_FAKEKEY123");
        // password intentionally left unset (null) - schema allows this
        HttpClientSpec spec = new HttpClientSpec("digest-service", "https://api.example.com", authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(capability, spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/resource");

        // When
        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        // Then
        assertThat(clientRequest.getChallengeResponse().getIdentifier()).isEqualTo("sk_test_FAKEKEY123");
        assertThat(String.valueOf(clientRequest.getChallengeResponse().getSecret())).isEqualTo("");
    }

    private Capability getCapability() throws Exception {
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                binds:
                  - namespace: "registry"
                    location: "file:///./src/test/resources/tutorial/shared/secrets.yaml"
                    keys:
                      notion_token: "notion_api_key"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "orders-api"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                  consumes: []
                """.formatted(schemaVersion));
        return new Capability(spec);
    }
}
