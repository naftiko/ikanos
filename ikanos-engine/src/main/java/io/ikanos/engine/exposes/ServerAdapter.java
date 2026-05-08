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
package io.ikanos.engine.exposes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Protocol;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.SecretVerifier;
import org.restlet.security.Verifier;
import io.ikanos.Capability;
import io.ikanos.engine.Adapter;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.util.BindingKeysSpec;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Base class for server adapters. All HTTP-based adapters share the same Restlet {@link Server}
 * lifecycle: create, start, stop. Subclasses configure routing in their constructor and call
 * {@link #initServer(String, int, Restlet)} to wire the transport.
 */
public abstract class ServerAdapter extends Adapter {

    private final Capability capability;
    private final ServerSpec spec;
    private Server server;

    public ServerAdapter(Capability capability, ServerSpec spec) {
        this.capability = capability;
        this.spec = spec;
    }

    /**
     * Initialize the Restlet HTTP server. Subclasses call this after building their router/chain.
     */
    protected void initServer(String address, int port, Restlet handler) {
        this.server = new Server(Protocol.HTTP, address, port);
        this.server.setContext(new Context());

        // TODO: Make idle timeout configurable
        this.server.getContext().getParameters().add("socketTimeout", "12000");

        this.server.setNext(handler);
    }

    public Capability getCapability() {
        return capability;
    }

    public ServerSpec getSpec() {
        return spec;
    }

    public Server getServer() {
        return server;
    }

    @Override
    public void start() throws Exception {
        if (server != null) {
            server.start();
        }
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Extracts all allowed variable names from the capability spec's bindings. These are the
     * variable names defined in the binds keys mapping.
     *
     * @param spec The Naftiko spec
     * @return Set of allowed variable names from binds declarations
     */
    public static Set<String> extractAllowedVariables(IkanosSpec spec) {
        Set<String> allowed = new HashSet<>();

        if (spec == null || spec.getBinds() == null) {
            return allowed;
        }

        for (BindingSpec bind : spec.getBinds()) {
            BindingKeysSpec keysSpec = bind.getKeys();
            if (keysSpec != null && keysSpec.getKeys() != null) {
                allowed.addAll(keysSpec.getKeys().keySet());
            }
        }

        return allowed;
    }

    /**
     * Builds the Restlet handler chain, optionally wrapping the next restlet with an
     * authentication filter based on the adapter's spec. Subclasses may override
     * {@link #createOAuth2Restlet} to provide adapter-specific OAuth 2.1 behaviour.
     */
    protected Restlet buildServerChain(Restlet next) {
        AuthenticationSpec authentication = getSpec().getAuthentication();
        if (authentication == null || authentication.getType() == null) {
            return next;
        }

        if ("basic".equals(authentication.getType())
                || "digest".equals(authentication.getType())) {
            return buildChallengeAuthenticator(authentication, next);
        }

        if ("oauth2".equals(authentication.getType())
                && authentication instanceof OAuth2AuthenticationSpec oauth2) {
            return createOAuth2Restlet(oauth2, next);
        }

        Set<String> allowedVariables = extractAllowedVariables(getCapability().getSpec());
        return new ServerAuthenticationRestlet(authentication, next, allowedVariables);
    }

    /**
     * Creates the OAuth 2.1 authentication restlet. Subclasses may override to return an
     * adapter-specific variant (e.g. MCP's Protected Resource Metadata extension).
     */
    protected Restlet createOAuth2Restlet(OAuth2AuthenticationSpec oauth2, Restlet next) {
        return new OAuth2AuthenticationRestlet(oauth2, next);
    }

    private Restlet buildChallengeAuthenticator(AuthenticationSpec authentication, Restlet next) {
        ChallengeScheme scheme = "digest".equals(authentication.getType())
                ? ChallengeScheme.HTTP_DIGEST
                : ChallengeScheme.HTTP_BASIC;

        Set<String> allowedVariables = extractAllowedVariables(getCapability().getSpec());
        ChallengeAuthenticator authenticator =
                new ChallengeAuthenticator(next.getContext(), false, scheme, "ikanos");
        authenticator.setVerifier(new SecretVerifier() {

            @Override
            public int verify(String identifier, char[] secret) {
                String expectedUsername = null;
                char[] expectedPassword = null;

                if (authentication instanceof BasicAuthenticationSpec basic) {
                    expectedUsername = resolveTemplate(basic.getUsername(), allowedVariables);
                    expectedPassword = resolveTemplateChars(basic.getPassword(), allowedVariables);
                } else if (authentication instanceof DigestAuthenticationSpec digest) {
                    expectedUsername = resolveTemplate(digest.getUsername(), allowedVariables);
                    expectedPassword = resolveTemplateChars(digest.getPassword(), allowedVariables);
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

    private static String resolveTemplate(String value, Set<String> allowedVariables) {
        if (value == null) {
            return null;
        }
        Map<String, Object> env = new HashMap<>();
        if (value.contains("{{") && value.contains("}}")) {
            for (String varName : allowedVariables) {
                String varValue = System.getenv(varName);
                if (varValue != null) {
                    env.put(varName, varValue);
                }
            }
        }
        return Resolver.resolveMustacheTemplate(value, env);
    }

    private static char[] resolveTemplateChars(char[] value, Set<String> allowedVariables) {
        if (value == null) {
            return null;
        }
        String resolved = resolveTemplate(new String(value), allowedVariables);
        return resolved == null ? null : resolved.toCharArray();
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean secureEquals(char[] expected, char[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                new String(expected).getBytes(StandardCharsets.UTF_8),
                new String(actual).getBytes(StandardCharsets.UTF_8));
    }

}