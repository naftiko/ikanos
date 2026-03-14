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

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.Resolver;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.InputParameterSpec;
import org.restlet.Request;
import org.restlet.data.Method;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-regression test for the value field support with Mustache templates in forward spec.
 * Tests that input parameters with "value" field are properly resolved for API forwarding.
 */
public class ForwardValueFieldTest {

    private Capability capability;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/http-forward-value-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Capability file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);
    }   

    @Test
    public void testValueFieldResolutionInForwardSpec() throws Exception {
        // Verify the HTTP client adapter exists and has input parameters configured
        HttpClientAdapter httpAdapter = null;
        for (var adapter : capability.getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter) {
                HttpClientAdapter httpClientAdapter = (HttpClientAdapter) adapter;
                if (httpClientAdapter.getHttpClientSpec().getNamespace().equals("external")) {
                    httpAdapter = httpClientAdapter;
                    break;
                }
            }
        }

        assertNotNull(httpAdapter, "HTTP client adapter for namespace 'external' should exist");

        // Verify input parameters are configured
        assertFalse(httpAdapter.getHttpClientSpec().getInputParameters().isEmpty(),
                "HTTP client spec should have input parameters configured");

        // Find the value input parameters
        InputParameterSpec valueParam = null;
        InputParameterSpec keyParam = null;

        for (InputParameterSpec param : httpAdapter.getHttpClientSpec().getInputParameters()) {
            if ("X-API-Version".equals(param.getName())) {
                valueParam = param;
            } else if ("X-API-Key".equals(param.getName())) {
                keyParam = param;
            }
        }

        assertNotNull(valueParam, "X-API-Version parameter with 'value' field should exist");
        assertNotNull(keyParam, "X-API-Key parameter with 'value' field should exist");

        assertEquals("2026-03-03", valueParam.getValue(),
                "X-API-Version 'value' field should be set to '2026-03-03'");
        assertEquals("secret-key-123", keyParam.getValue(),
                "X-API-Key 'value' field should be set to 'secret-key-123'");
    }

    @Test
    public void testValueFieldAppliedToRequest() throws Exception {
        // Find the HTTP client adapter
        HttpClientAdapter httpAdapter = null;
        for (var adapter : capability.getClientAdapters()) {
            if (adapter instanceof HttpClientAdapter) {
                HttpClientAdapter httpClientAdapter = (HttpClientAdapter) adapter;
                if (httpClientAdapter.getHttpClientSpec().getNamespace().equals("external")) {
                    httpAdapter = httpClientAdapter;
                    break;
                }
            }
        }

        assertNotNull(httpAdapter, "HTTP client adapter should exist");

        // Create a test request
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/test");

        // Apply input parameters to the request
        Map<String, Object> parameters = new ConcurrentHashMap<>();
        Resolver.resolveInputParametersToRequest(clientRequest,
                httpAdapter.getHttpClientSpec().getInputParameters(), parameters);

        // Verify both headers are set
        String apiVersionHeader = clientRequest.getHeaders().getFirstValue("X-API-Version", true);
        String apiKeyHeader = clientRequest.getHeaders().getFirstValue("X-API-Key", true);

        assertNotNull(apiVersionHeader, "X-API-Version header should be set");
        assertEquals("2026-03-03", apiVersionHeader,
                "X-API-Version header should have the value from 'value' field");

        assertNotNull(apiKeyHeader, "X-API-Key header should be set");
        assertEquals("secret-key-123", apiKeyHeader,
            "X-API-Key header should have the value from 'value' field");

        // Verify that both values were added to parameters map for template resolution
        assertTrue(parameters.containsKey("X-API-Version"),
                "X-API-Version should be in the parameters map");
        assertTrue(parameters.containsKey("X-API-Key"),
                "X-API-Key should be in the parameters map");
    }

    @Test
    public void testValueFieldWithMustacheTemplate() throws Exception {
        // This test verifies that the value field supports Mustache template syntax
        // by testing the Resolver directly with a value field that has template syntax

        InputParameterSpec spec = new InputParameterSpec();
        spec.setName("X-Request-Id");
        spec.setIn("header");
        spec.setValue("{{requestId}}");  // Value with Mustache template

        Request request = new Request(Method.GET, "https://api.example.com/v1/test");
        Map<String, Object> parameters = new ConcurrentHashMap<>();
        parameters.put("requestId", "req-12345");

        // Apply the input parameter
        Resolver.resolveInputParametersToRequest(request,
                java.util.Collections.singletonList(spec), parameters);

        // Verify the header was set with the resolved template value
        String headerValue = request.getHeaders().getFirstValue("X-Request-Id", true);
        assertNotNull(headerValue, "X-Request-Id header should be set");
        assertEquals("req-12345", headerValue,
                "X-Request-Id header should have the resolved Mustache template value");
    }
}
