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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.data.ChallengeScheme;
import org.restlet.routing.Router;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.SecretVerifier;
import org.restlet.security.Verifier;
import io.naftiko.Capability;
import io.naftiko.engine.Resolver;
import io.naftiko.spec.consumes.AuthenticationSpec;
import io.naftiko.spec.consumes.BasicAuthenticationSpec;
import io.naftiko.spec.consumes.DigestAuthenticationSpec;
import io.naftiko.spec.ExternalRefSpec;
import io.naftiko.spec.ExternalRefKeysSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerSpec;

/**
 * Implementation of the ServerAdapter abstract class that sets up an HTTP server using the Restlet
 * Framework acting as a spec-driven API server.
 */
public class RestServerAdapter extends ServerAdapter {

    private final Server server;
    private final Router router;

    public RestServerAdapter(Capability capability, RestServerSpec serverSpec) {
        super(capability, serverSpec);
        this.server = new Server(Protocol.HTTP, serverSpec.getAddress(), serverSpec.getPort());
        this.router = new Router();

        for (RestServerResourceSpec res : getRestServerSpec().getResources()) {
            String pathTemplate = toUriTemplate(res.getPath());
            Restlet resourceRestlet = new RestResourceRestlet(capability, serverSpec, res);
            TemplateRoute route = getRouter().attach(pathTemplate, resourceRestlet);
            route.getTemplate().getVariables().put("path", new Variable(Variable.TYPE_URI_PATH));
        }

        this.server.setNext(buildServerChain(serverSpec));
    }

    private Restlet buildServerChain(RestServerSpec serverSpec) {
        Restlet next = this.router;
        AuthenticationSpec authentication = serverSpec.getAuthentication();

        if (authentication == null || authentication.getType() == null) {
            return next;
        }

        if ("basic".equals(authentication.getType()) || "digest".equals(authentication.getType())) {
            return buildChallengeAuthenticator(authentication, next);
        }

        // Extract allowed variable names from capability's external refs
        Set<String> allowedVariables = extractAllowedVariables(getCapability().getSpec());
        return new RestServerAuthenticationRestlet(authentication, next, allowedVariables);
    }

    private Restlet buildChallengeAuthenticator(AuthenticationSpec authentication, Restlet next) {
        ChallengeScheme scheme = "digest".equals(authentication.getType())
                ? ChallengeScheme.HTTP_DIGEST
                : ChallengeScheme.HTTP_BASIC;

        ChallengeAuthenticator authenticator =
                new ChallengeAuthenticator(this.router.getContext(), false, scheme, "naftiko");
        authenticator.setVerifier(new SecretVerifier() {

            @Override
            public int verify(String identifier, char[] secret) {
                String expectedUsername = null;
                char[] expectedPassword = null;

                if (authentication instanceof BasicAuthenticationSpec basic) {
                    expectedUsername = resolveTemplate(basic.getUsername());
                    expectedPassword = resolveTemplateChars(basic.getPassword());
                } else if (authentication instanceof DigestAuthenticationSpec digest) {
                    expectedUsername = resolveTemplate(digest.getUsername());
                    expectedPassword = resolveTemplateChars(digest.getPassword());
                }

                if (expectedUsername == null || expectedPassword == null || identifier == null
                        || secret == null) {
                    return Verifier.RESULT_INVALID;
                }

                boolean usernameMatches = secureEquals(expectedUsername, identifier);
                boolean passwordMatches = secureEquals(expectedPassword, secret);

                return (usernameMatches && passwordMatches) ? Verifier.RESULT_VALID
                        : Verifier.RESULT_INVALID;
            }
        });
        authenticator.setNext(next);
        return authenticator;
    }

    private static String resolveTemplate(String value) {
        if (value == null) {
            return null;
        }

        Map<String, Object> env = new HashMap<>();

        if (value.contains("{{") && value.contains("}}")) {
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                env.put(entry.getKey(), entry.getValue());
            }
        }

        return Resolver.resolveMustacheTemplate(value, env);
    }

    private static char[] resolveTemplateChars(char[] value) {
        if (value == null) {
            return null;
        }

        String resolved = resolveTemplate(new String(value));
        return resolved == null ? null : resolved.toCharArray();
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }

        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean secureEquals(char[] expected, char[] actual) {
        if (expected == null || actual == null) {
            return false;
        }

        return MessageDigest.isEqual(new String(expected).getBytes(StandardCharsets.UTF_8),
                new String(actual).getBytes(StandardCharsets.UTF_8));
    }

    public RestServerSpec getRestServerSpec() {
        return (RestServerSpec) getSpec();
    }

    public Server getServer() {
        return server;
    }

    public Router getRouter() {
        return router;
    }

    /**
     * Extracts all allowed variable names from the capability spec's external references.
     * These are the variable names defined in the externalRefs keys mapping.
     * 
     * @param spec The Naftiko spec
     * @return Set of allowed variable names from externalRefs declarations
     */
    private static Set<String> extractAllowedVariables(io.naftiko.spec.NaftikoSpec spec) {
        Set<String> allowed = new HashSet<>();
        
        if (spec == null || spec.getExternalRefs() == null) {
            return allowed;
        }
        
        for (ExternalRefSpec ref : spec.getExternalRefs()) {
            ExternalRefKeysSpec keysSpec = ref.getKeys();
            if (keysSpec != null && keysSpec.getKeys() != null) {
                // The keys are the variable names used for template injection
                allowed.addAll(keysSpec.getKeys().keySet());
            }
        }
        
        return allowed;
    }

    @Override
    public void start() throws Exception {
        getServer().start();
    }

    @Override
    public void stop() throws Exception {
        getServer().stop();
    }

}
