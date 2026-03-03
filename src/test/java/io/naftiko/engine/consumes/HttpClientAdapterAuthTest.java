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
package io.naftiko.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;
import io.naftiko.spec.consumes.HttpClientSpec;

public class HttpClientAdapterAuthTest {

    @Test
    public void bearerAuthenticationShouldSetAuthorizationHeader() {
        HttpClientSpec spec = new HttpClientSpec("notion", "https://api.notion.com/v1/", null);

        BearerAuthenticationSpec authentication = new BearerAuthenticationSpec();
        authentication.setType("bearer");
        authentication.setToken("{{notion_api_key}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(null, spec);
        Request clientRequest = new Request(Method.GET, "https://api.notion.com/v1/pages");

        Map<String, Object> parameters = new ConcurrentHashMap<>();
        parameters.put("notion_api_key", "ntn_test_abc123");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), parameters);

        assertNotNull(clientRequest.getChallengeResponse(),
                "Bearer auth should use Restlet ChallengeResponse");
        assertEquals("ntn_test_abc123", clientRequest.getChallengeResponse().getRawValue(),
                "Bearer token should be set as challenge raw value");
    }
}
