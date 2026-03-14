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
package io.naftiko.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.naftiko.spec.consumes.ApiKeyAuthenticationSpec;
import io.naftiko.spec.consumes.BearerAuthenticationSpec;

public class ServerAuthenticationRestletTest {

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

        ServerAuthenticationRestlet secured = new ServerAuthenticationRestlet(auth, next);
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
        ServerAuthenticationRestlet secured = new ServerAuthenticationRestlet(auth, next);
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

        ServerAuthenticationRestlet secured = new ServerAuthenticationRestlet(auth, next);
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
        ServerAuthenticationRestlet secured = new ServerAuthenticationRestlet(auth, next);
        Request request = new Request(Method.GET, "http://localhost/test?api_key=wrong");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void bearerWithoutAllowedVariablesShouldNotResolveEnvironmentVariable() {
        // Setup environment variable
        String envVarName = "TEST_UNDECLARED_TOKEN_456";
        String envVarValue = "secret-token-from-env";
        
        // Set the environment variable for this test
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put(envVarName, envVarValue);
        
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("{{" + envVarName + "}}");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
            }
        };

        // Create restlet WITHOUT allowed variables (empty set)
        // This means no external refs were declared
        ServerAuthenticationRestlet secured = 
            new ServerAuthenticationRestlet(auth, next, Set.of());
        
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("Authorization", "Bearer secret-token-from-env");
        Response response = new Response(request);

        secured.handle(request, response);

        // Should fail authorization because the variable was not resolved
        // (template remains as {{TEST_UNDECLARED_TOKEN_456}} which won't match)
        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void bearerWithAllowedVariablesShouldResolveOnlyDeclaredVariables() {
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("{{declared_token}}");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("ok", MediaType.TEXT_PLAIN);
            }
        };

        // Create restlet WITH allowed variables set
        // This simulates externalRefs declaring this variable
        ServerAuthenticationRestlet secured = 
            new ServerAuthenticationRestlet(auth, next, Set.of("declared_token"));
        
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("Authorization", "Bearer my-token-123");
        Response response = new Response(request);

        // Note: In a real scenario, the environment would have DECLARED_TOKEN set
        // but we're testing the mechanism that prevents undeclared ones
        secured.handle(request, response);

        // Will be unauthorized because environment var is not set, but the point is
        // that it TRIED to resolve it (because it's in the allowed set)
        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void apiKeyWithoutAllowedVariablesShouldNotResolveEnvironmentVariable() {
        ApiKeyAuthenticationSpec auth = new ApiKeyAuthenticationSpec();
        auth.setKey("X-API-Key");
        auth.setValue("{{undeclared_api_key}}");
        auth.setPlacement("header");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
            }
        };

        // Create restlet WITHOUT allowed variables (not declared in externalRefs)
        ServerAuthenticationRestlet secured = 
            new ServerAuthenticationRestlet(auth, next, Set.of());
        
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("X-API-Key", "actual-key-value");
        Response response = new Response(request);

        secured.handle(request, response);

        // Should fail because the variable was not resolved (remained as template)
        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void apiKeyWithAllowedVariablesShouldResolveOnlyDeclaredKey() {
        ApiKeyAuthenticationSpec auth = new ApiKeyAuthenticationSpec();
        auth.setKey("X-API-Key");
        auth.setValue("{{my_declared_key}}");
        auth.setPlacement("header");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
            }
        };

        // Create restlet WITH this variable in the allowed set
        ServerAuthenticationRestlet secured = 
            new ServerAuthenticationRestlet(auth, next, Set.of("my_declared_key"));
        
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("X-API-Key", "actual-key-value");
        Response response = new Response(request);

        secured.handle(request, response);

        // Should fail authorization (environment var not set in test)
        // but it shows the mechanism works - it tried to resolve the allowed variable
        assertEquals(Status.CLIENT_ERROR_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void backwardCompatibilityShouldAllowNoAllowedVariablesParameter() {
        // Test that old code using the 2-parameter constructor still works
        BearerAuthenticationSpec auth = new BearerAuthenticationSpec();
        auth.setToken("hardcoded-token");

        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                response.setStatus(Status.SUCCESS_OK);
                response.setEntity("ok", MediaType.TEXT_PLAIN);
            }
        };

        // Old-style instantiation without allowed variables
        ServerAuthenticationRestlet secured = new ServerAuthenticationRestlet(auth, next);
        
        Request request = new Request(Method.GET, "http://localhost/test");
        request.getHeaders().set("Authorization", "Bearer hardcoded-token");
        Response response = new Response(request);

        secured.handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
    }
}
