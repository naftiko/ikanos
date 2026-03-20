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
package io.naftiko.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Method;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.BasicAuthenticationSpec;
import io.naftiko.spec.consumes.DigestAuthenticationSpec;
import io.naftiko.spec.consumes.HttpClientSpec;

public class HttpClientAdapterTest {

    @Test
    public void basicAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("basic", "https://api.example.com", null);
        BasicAuthenticationSpec authentication = new BasicAuthenticationSpec();
        authentication.setType("basic");
        authentication.setUsername("{{username}}");
        authentication.setPassword("{{password}}".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");
        Map<String, Object> parameters = new ConcurrentHashMap<>();
        parameters.put("username", "alice");
        parameters.put("password", "secret");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), parameters);

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("alice", clientRequest.getChallengeResponse().getIdentifier());
                assertNotNull(clientRequest.getChallengeResponse().getSecret());
    }

    @Test
    public void digestAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("digest", "https://api.example.com", null);
        DigestAuthenticationSpec authentication = new DigestAuthenticationSpec();
        authentication.setType("digest");
        authentication.setUsername("digest-user");
        authentication.setPassword("digest-pass".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("digest-user", clientRequest.getChallengeResponse().getIdentifier());
        assertNotNull(clientRequest.getChallengeResponse().getSecret());
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

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of("token", "abc123"));

        assertEquals("https://api.example.com/v1/items?api_key=abc123",
                clientRequest.getResourceRef().toString());
    }

    @Test
    public void shouldForwardExistingServerChallengeResponseWhenClientAuthIsAbsent() {
        HttpClientSpec spec = new HttpClientSpec("forwarded", "https://api.example.com", null);
        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);

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
}