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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;

public class ApiServerAuthenticationRestletTest {

    @Test
    public void bearerShouldAuthorizeMatchingToken() {
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("token-123");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("ok", MediaType.TEXT_PLAIN);
            }
        };

        ApiServerAuthenticationRestlet secured = new ApiServerAuthenticationRestlet(auth, next);
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("Authorization", "Bearer token-123");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
    }

    @Test
    public void bearerShouldRejectMismatchedToken() {
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("token-123");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
            }
        };
        ApiServerAuthenticationRestlet secured = new ApiServerAuthenticationRestlet(auth, next);
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("Authorization", "Bearer bad-token");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void apiKeyHeaderShouldAuthorizeMatchingValue() {
        ApiKeyAuthenticationSpec auth = new ApiKeyAuthenticationSpec();
        auth.setKey("X-API-Key");
        auth.setValue("abc123");
        auth.setPlacement("header");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
            }
        };

        ApiServerAuthenticationRestlet secured = new ApiServerAuthenticationRestlet(auth, next);
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("X-API-Key", "abc123");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
    }

    @Test
    public void apiKeyQueryShouldRejectMismatchedValue() {
        ApiKeyAuthenticationSpec auth = new ApiKeyAuthenticationSpec();
        auth.setKey("api_key");
        auth.setValue("abc123");
        auth.setPlacement("query");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
            }
        };
        ApiServerAuthenticationRestlet secured = new ApiServerAuthenticationRestlet(auth, next);
        Request request = new Request(Method.GET, "http://localhost/test?api_key=wrong");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }
}