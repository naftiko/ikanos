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
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Method;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HttpClientAdapterTest {

    @Test
    public void basicAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("basic", "https://api.example.com", null);
        BasicAuthenticationSpec authentication = new BasicAuthenticationSpec();
        authentication.setType("basic");
        authentication.setUsername("{{username}}");
        authentication.setPassword("{{password}}".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("username", "alice", "password", "secret")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("alice", clientRequest.getChallengeResponse().getIdentifier());
        // Previously this only asserted non-null, which let bug S2116 slip through:
        // calling toString() on a char[] returned the array identity (e.g. "[C@1a2b3c")
        // instead of resolving the {{password}} Mustache placeholder. Compare the actual
        // resolved secret to guard against that regression.
        assertEquals("secret",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    @Test
    public void digestAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("digest", "https://api.example.com", null);
        DigestAuthenticationSpec authentication = new DigestAuthenticationSpec();
        authentication.setType("digest");
        authentication.setUsername("digest-user");
        authentication.setPassword("digest-pass".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("digest-user", clientRequest.getChallengeResponse().getIdentifier());
        // Previously this only asserted non-null, which let bug S2116 slip through:
        // calling toString() on a char[] returned the array identity (e.g. "[C@1a2b3c")
        // instead of the literal password. Compare the actual secret content to guard
        // against that regression.
        assertEquals("digest-pass",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    @Test
    public void apiKeyQueryAuthenticationShouldAppendQueryParameter() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("api_key");
        authentication.setPlacement("query");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "abc123")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertEquals("https://api.example.com/v1/items?api_key=abc123",
                clientRequest.getResourceRef().toString());
    }

    @Test
    public void apiKeyHeaderAuthenticationShouldSetHeaderForNonReservedKey() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("X-Custom-Key");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "abc123")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertEquals("abc123", clientRequest.getHeaders().getFirstValue("X-Custom-Key"));
    }

    @Test
    public void apiKeyHeaderAuthenticationWithAuthorizationKeyShouldRouteThroughChallengeResponse() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("Authorization");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "SENTINEL-TOKEN-12345")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        // Authorization is a reserved header: Restlet silently drops it if added via
        // getHeaders().add() (see #698). It must go through the ChallengeResponse instead.
        assertEquals(null, clientRequest.getHeaders().getFirstValue("Authorization", true));
        assertNotNull(clientRequest.getChallengeResponse());

        // AuthenticatorUtils.formatResponse() is the exact method Restlet's engine calls to
        // serialize the ChallengeResponse into the outbound Authorization header value, so
        // this proves what will actually be sent on the wire, with no scheme prefix and no
        // leading space (asserting on the untrimmed value on purpose: a regression that
        // reintroduces the leading space injected by technicalName + " " must fail here).
        String wireValue = org.restlet.engine.security.AuthenticatorUtils.formatResponse(
                clientRequest.getChallengeResponse(), clientRequest,
                new org.restlet.util.Series<>(org.restlet.data.Header.class));
        assertEquals("SENTINEL-TOKEN-12345", wireValue);
    }

    @Test
    public void apiKeyHeaderAuthenticationWithLowercaseAuthorizationKeyShouldRouteThroughChallengeResponse() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("authorization");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "pk_live_abc")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        // Untrimmed on purpose — see comment above.
        String wireValue = org.restlet.engine.security.AuthenticatorUtils.formatResponse(
                clientRequest.getChallengeResponse(), clientRequest,
                new org.restlet.util.Series<>(org.restlet.data.Header.class));
        assertEquals("pk_live_abc", wireValue);
    }

    @Test
    public void shouldForwardExistingServerChallengeResponseWhenClientAuthIsAbsent() {
        HttpClientSpec spec = new HttpClientSpec("forwarded", "https://api.example.com", null);
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);

        Request serverRequest = new Request(Method.GET, "https://upstream.example.com");
        ChallengeResponse upstreamChallenge = new ChallengeResponse(ChallengeScheme.HTTP_BASIC);
        upstreamChallenge.setIdentifier("bob");
        upstreamChallenge.setSecret("forwarded-secret".toCharArray());
        serverRequest.setChallengeResponse(upstreamChallenge);

        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");
        adapter.setChallengeResponse(serverRequest, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("bob", clientRequest.getChallengeResponse().getIdentifier());
        assertEquals("forwarded-secret",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    @Test
    public void setChallengeResponseShouldThrowWhenApiKeyPlacementIsMissing() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("X-Dock-Key");
        authentication.setValue("MY_API_KEY");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.setChallengeResponse(null, clientRequest,
                        clientRequest.getResourceRef().toString(), Map.of()));
        assertEquals("Placement is required for apikey authentication (expected: header or query)",
                ex.getMessage());
    }

    @Test
    public void prepareShouldApplyResourceLevelQueryParametersBetweenAdapterAndOperation()
            throws Exception {
        Request request = prepare("list-things", Map.of("filter", "a eq 1"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&res-query=from-resource&%24filter=a+eq+1&op-query=from-operation",
                request.getResourceRef().toString(),
                "#909: resource-level query parameters should be sent, after the adapter level"
                        + " and before the operation level");
    }

    @Test
    public void prepareShouldApplyResourceLevelHeader() throws Exception {
        Request request = prepare("list-things", Map.of("filter", "a eq 1"));

        assertEquals("from-resource", request.getHeaders().getFirstValue("X-Res-Header"),
                "#909: a resource-level header should be sent");
    }

    @Test
    public void prepareShouldLetOperationParameterOverrideBroaderParametersWithSameName()
            throws Exception {
        Request request = prepare("list-top", Map.of("filter", "a eq 1"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter"
                + "&%24filter=a+eq+1&res-query=from-operation&top=5",
                request.getResourceRef().toString(),
                "#909: an operation parameter with the same name and location as an adapter or"
                        + " resource parameter should replace it, not be sent a second time");
    }

    @Test
    public void prepareShouldApplyResourceLevelInputParametersToEveryOperation() throws Exception {
        Request request = prepare("list-other", Map.of("filter", "b eq 2"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&res-query=from-resource&%24filter=b+eq+2",
                request.getResourceRef().toString(),
                "#909: an operation without parameters should still send the resource's");
    }

    @Test
    public void prepareShouldResolveOverridingParameterAfterTheParametersItDependsOn()
            throws Exception {
        Request request = prepare("list-dependent", Map.of());

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&status=active&res-query=active",
                request.getResourceRef().toString(),
                "#909: an operation parameter that replaces a resource parameter should be"
                        + " resolved in its own position, after the parameters it uses");
    }

    @Test
    public void prepareShouldResolveBroaderParameterBeforeANarrowerOneReplacesWhatItUses()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                adapterDependencySpec());

        Request request = adapter.prepare("list", new HashMap<>()).getRequest();

        assertEquals("https://probe.example/v1/things?q=active&status=inactive",
                request.getResourceRef().toString(),
                "#909: parameters should be resolved in declaration order, as before the merge,"
                        + " so an adapter template still sees the adapter value it uses; only"
                        + " the replaced parameter should not be sent");
    }

    private Request prepare(String operation, Map<String, Object> arguments) throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                resourceParametersSpec());
        return adapter.prepare(operation, new HashMap<>(arguments)).getRequest();
    }

    private HttpClientSpec resourceParametersSpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      inputParameters:
                        adapter-query:
                          in: query
                          value: "from-adapter"
                        top:
                          in: query
                          value: "10"
                      resources:
                        things:
                          path: "/things"
                          inputParameters:
                            res-query:
                              in: query
                              value: "from-resource"
                            X-Res-Header:
                              in: header
                              value: "from-resource"
                            filter:
                              in: query
                              wireName: "$filter"
                          operations:
                            list-things:
                              method: GET
                              inputParameters:
                                op-query:
                                  in: query
                                  value: "from-operation"
                            list-other:
                              method: GET
                            list-top:
                              method: GET
                              inputParameters:
                                res-query:
                                  in: query
                                  value: "from-operation"
                                top:
                                  in: query
                                  value: "5"
                            list-dependent:
                              method: GET
                              inputParameters:
                                status:
                                  in: query
                                  value: "active"
                                res-query:
                                  in: query
                                  value: "{{status}}"
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private HttpClientSpec adapterDependencySpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      inputParameters:
                        status:
                          in: query
                          value: "active"
                        q:
                          in: query
                          value: "{{status}}"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: GET
                              inputParameters:
                                status:
                                  in: query
                                  value: "inactive"
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private Capability getMockCapability(Map<String, Object> bindings) {
        Capability capability = mock(Capability.class);
        when(capability.getBindings()).thenReturn(bindings);
        return capability;
    }
}
