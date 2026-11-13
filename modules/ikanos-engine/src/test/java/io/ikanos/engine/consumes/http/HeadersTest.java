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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

public class HeadersTest {

    @Test
    public void setHeadersDoesNotOverrideValueBasedHeader() {
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com", null);

        InputParameterSpec notionVersion = new InputParameterSpec();
        notionVersion.setName("Notion-Version");
        notionVersion.setIn("header");
        notionVersion.setValue("2025-09-03");
        spec.setInputParameters(Map.of("Notion-Version", notionVersion));

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        Map<String, Object> params = new ConcurrentHashMap<>();
        Resolver.resolveInputParametersToRequest(request, spec.getInputParameters(), params);
        adapter.setHeaders(request);

        String header = request.getHeaders().getFirstValue("Notion-Version", true);
        assertNotNull(header, "Notion-Version header should remain present");
        assertEquals("2025-09-03", header,
                "Notion-Version should not be overwritten when declared with value");
    }

    @Test
    public void resolveInputParametersShouldSetAcceptedMediaTypesWhenAcceptIsAHeaderInput() {
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        InputParameterSpec accept = new InputParameterSpec();
        accept.setName("Accept");
        accept.setIn("header");
        accept.setValue("application/xml");

        Resolver.resolveInputParametersToRequest(request, List.of(accept),
                new ConcurrentHashMap<>());

        assertEquals(MediaType.APPLICATION_XML,
                request.getClientInfo().getAcceptedMediaTypes().get(0).getMetadata(),
                "#1001: Restlet 3 only sends Accept from the client info, never from a raw header");
    }

    @Test
    public void setHeadersShouldSetAgentWhenUserAgentIsAnAdapterHeader() {
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com", null);
        InputParameterSpec userAgent = new InputParameterSpec();
        userAgent.setName("User-Agent");
        userAgent.setIn("header");
        userAgent.setValue("ikanos-test/1.0");
        spec.setInputParameters(Map.of("User-Agent", userAgent));
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        new HttpClientAdapter(null, spec).setHeaders(request);

        assertEquals("ikanos-test/1.0", request.getClientInfo().getAgent(),
                "#1001: Restlet 3 only sends User-Agent from the client info, never from a raw "
                        + "header");
    }

    @Test
    public void setHeadersShouldSendAnAdapterHeaderUnderItsWireName() {
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com", null);
        InputParameterSpec revision = new InputParameterSpec();
        revision.setName("If-Match");
        revision.setWireName("X-Revision");
        revision.setIn("header");
        revision.setValue("v1");
        spec.setInputParameters(Map.of("If-Match", revision));
        Request request = new Request(Method.PUT, "https://api.example.com/v1/pages");

        new HttpClientAdapter(null, spec).setHeaders(request);

        assertEquals("v1", request.getHeaders().getFirstValue("X-Revision", true));
        assertTrue(request.getConditions().getMatch().isEmpty(),
                "the logical name If-Match is not the header sent: no precondition applies");
    }

    @Test
    public void setHeadersShouldSendAnAdapterHeaderNamedAuthorizationUnderAnotherWireName() {
        BearerAuthenticationSpec authentication = new BearerAuthenticationSpec();
        authentication.setToken("secret");
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com",
                authentication);
        InputParameterSpec token = new InputParameterSpec();
        token.setName("Authorization");
        token.setWireName("X-Api-Token");
        token.setIn("header");
        token.setValue("t-1");
        spec.setInputParameters(Map.of("Authorization", token));
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        new HttpClientAdapter(null, spec).setHeaders(request);

        assertEquals("t-1", request.getHeaders().getFirstValue("X-Api-Token", true),
                "only the Authorization wire header yields to the declared authentication");
    }

    private static InputParameterSpec ifMatch(String value) {
        InputParameterSpec ifMatch = new InputParameterSpec();
        ifMatch.setName("If-Match");
        ifMatch.setIn("header");
        ifMatch.setValue(value);
        return ifMatch;
    }

    @Test
    public void resolveInputParametersShouldRefuseAnUnresolvedIfMatchOfAPutWithNoParameters() {
        Request request = new Request(Method.PUT, "https://api.example.com/v1/items/1");

        assertThrows(StandardRequestHeaders.InvalidPreconditionException.class,
                () -> Resolver.resolveInputParametersToRequest(request,
                        List.of(ifMatch("{{etag}}")), new ConcurrentHashMap<>()),
                "#1001: leaving the If-Match out would make the write unconditional");
    }

    @Test
    public void resolveInputParametersShouldRefuseAnIfMatchOfAPutWhoseVariableIsMissing() {
        Request request = new Request(Method.PUT, "https://api.example.com/v1/items/1");
        Map<String, Object> params = new ConcurrentHashMap<>(Map.of("other", "x"));

        assertThrows(StandardRequestHeaders.InvalidPreconditionException.class,
                () -> Resolver.resolveInputParametersToRequest(request,
                        List.of(ifMatch("W/\"{{etag}}\"")), params));
    }

    @Test
    public void resolveInputParametersShouldRefuseAnIfMatchOfAPutWhosePlaceholderIsNull() {
        Request request = new Request(Method.PUT, "https://api.example.com/v1/items/1");
        Map<String, Object> params = new HashMap<>();
        params.put("etag", null);
        params.put("other", "x");

        assertThrows(StandardRequestHeaders.InvalidPreconditionException.class,
                () -> Resolver.resolveInputParametersToRequest(request,
                        List.of(ifMatch("{{etag}}")), params));
    }

    @Test
    public void resolveInputParametersShouldLeaveOutAnUnresolvedIfMatchOfAGet() {
        Request request = new Request(Method.GET, "https://api.example.com/v1/items/1");

        Resolver.resolveInputParametersToRequest(request, List.of(ifMatch("{{etag}}")),
                new ConcurrentHashMap<>());

        assertTrue(request.getConditions().getMatch().isEmpty());
    }

    @Test
    public void resolveInputParametersShouldSendAResolvedIfMatchOfAPut() {
        Request request = new Request(Method.PUT, "https://api.example.com/v1/items/1");

        Resolver.resolveInputParametersToRequest(request, List.of(ifMatch("{{etag}}")),
                new ConcurrentHashMap<>(Map.of("etag", "\"v1\"")));

        assertEquals(1, request.getConditions().getMatch().size());
    }

    @Test
    public void resolveInputParametersUsesMustacheInValue() {
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        InputParameterSpec dynamicHeader = new InputParameterSpec();
        dynamicHeader.setName("X-Trace");
        dynamicHeader.setIn("header");
        dynamicHeader.setValue("trace-{{requestId}}");

        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("requestId", "abc-123");

        Resolver.resolveInputParametersToRequest(request,
                List.of(dynamicHeader), params);

        String header = request.getHeaders().getFirstValue("X-Trace", true);
        assertEquals("trace-abc-123", header,
                "value should support Mustache template resolution");
    }
}
