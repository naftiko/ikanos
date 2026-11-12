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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

public class HeadersTest {

    @Test
    public void resolveInputParametersShouldSetAcceptedMediaTypesWhenAcceptIsAHeaderInput() {
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        InputParameterSpec accept = new InputParameterSpec();
        accept.setName("Accept");
        accept.setIn("header");
        accept.setValue("application/xml");

        Resolver.resolveInputParametersToRequest(request, java.util.List.of(accept),
                new ConcurrentHashMap<>());

        assertEquals(org.restlet.data.MediaType.APPLICATION_XML,
                request.getClientInfo().getAcceptedMediaTypes().get(0).getMetadata(),
                "#1001: Restlet 3 only sends Accept from the client info, never from a raw header");
    }

    @Test
    public void resolveInputParametersShouldSetAgentWhenUserAgentIsAnAdapterHeader() {
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com", null);
        InputParameterSpec userAgent = new InputParameterSpec();
        userAgent.setName("User-Agent");
        userAgent.setIn("header");
        userAgent.setValue("ikanos-test/1.0");
        spec.setInputParameters(Map.of("User-Agent", userAgent));
        Request request = new Request(Method.GET, "https://api.example.com/v1/pages");

        // Adapter headers go through the Resolver since #1070
        Resolver.resolveInputParametersToRequest(request, spec.getInputParameters(),
                new ConcurrentHashMap<>());

        assertEquals("ikanos-test/1.0", request.getClientInfo().getAgent(),
                "#1001: Restlet 3 only sends User-Agent from the client info, never from a raw "
                        + "header");
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
                java.util.List.of(dynamicHeader), params);

        String header = request.getHeaders().getFirstValue("X-Trace", true);
        assertEquals("trace-abc-123", header,
                "value should support Mustache template resolution");
    }
}
