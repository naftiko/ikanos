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
    public void setHeadersDoesNotOverrideValueBasedHeader() {
        HttpClientSpec spec = new HttpClientSpec("test", "https://api.example.com", null);

        InputParameterSpec notionVersion = new InputParameterSpec();
        notionVersion.setName("Notion-Version");
        notionVersion.setIn("header");
        notionVersion.setValue("2025-09-03");
        spec.getInputParameters().add(notionVersion);

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
