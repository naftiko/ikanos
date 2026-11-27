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

import java.util.List;
import java.util.Map;
import org.restlet.Context;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Protocol;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.SecretVerifier;
import org.restlet.security.Verifier;
import io.ikanos.Capability;
import io.ikanos.engine.Adapter;
import io.ikanos.engine.util.Secrets;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.ServerSpec;

/**
 * Base class for server adapters. All HTTP-based adapters share the same Restlet {@link Server}
 * lifecycle: create, start, stop. Subclasses configure routing in their constructor and call
 * {@link #initServer(String, int, Restlet)} to wire the transport.
 *
 * <p>When several adapters share an address and port, the capability's {@link ServerHost} mounts
 * each adapter's handler on one shared server instead, and {@link #start()} and {@link #stop()}
 * leave that server to the host.</p>
 */
public abstract class ServerAdapter extends Adapter {

    /** Requests an adapter works on at once, per CPU, when its spec sets no maxRequestsInProgress (#920). */
    static final int DEFAULT_REQUESTS_IN_PROGRESS_PER_CPU = 16;

    private final Capability capability;
    private final ServerSpec spec;
    private Server server;
    private Restlet handler;
    /** The requests in progress {@link #limitRequestsInProgress} allows, or 0 without a limit. */
    private int requestsInProgressLimit;
    private boolean sharedServer;

    public ServerAdapter(Capability capability, ServerSpec spec) {
        this.capability = capability;
        this.spec = spec;
    }

    /**
     * Initialize the Restlet HTTP server. Subclasses call this after building their router/chain.
     * An exception that escapes the chain gets a {@code 500} instead of leaving the client
     * waiting (issue #897).
     */
    protected void initServer(String address, int port, Restlet handler) {
        this.server = createServer(address, port, requestsInProgressLimit);
        this.server.setNext(new UncaughtExceptionFilter(handler));
        this.handler = handler;
    }

    /**
     * How long a server connection may stay idle before the connector closes it, in milliseconds.
     * Jetty ignored the timeout Ikanos set, so listeners ran on its 30 s default (#918). Restlet's
     * Netty connector applies {@code connector.idleTimeout} to the whole connection, even while a
     * request is in progress, so a lower value would cut calls that used to succeed.
     */
    static final int IDLE_TIMEOUT_MILLIS = 30_000;

    /**
     * Configures a server connector exactly like the one each adapter opens on its own, so that a
     * listener shared by several adapters behaves like a dedicated one.
     */
    static Server createServer(String address, int port) {
        return createServer(address, port, 0);
    }

    /**
     * Same, for an adapter that allows {@code requestsInProgress} requests at once. On Restlet's
     * Netty connector each call runs on its own virtual thread ({@code threadPool.virtual}, the
     * default), so no thread pool caps the calls below that limit, as Jetty's 200 threads did
     * (#920): the adapter's own limit applies, and nothing needs sizing here.
     */
    static Server createServer(String address, int port, int requestsInProgress) {
        Server result = new Server(Protocol.HTTP, address, port);
        result.setContext(new Context());

        // Configurable idle timeout is tracked in https://github.com/naftiko/ikanos/issues/835.
        result.getContext().getParameters().add("connector.idleTimeout",
                Integer.toString(IDLE_TIMEOUT_MILLIS));
        // The client address is always the direct peer. With "true", Restlet would take it from
        // the caller-controlled X-Forwarded-For header, which would defeat the MCP
        // trustedNetworks check (issue #483).
        result.getContext().getParameters().add("useForwardedForHeader", "false");
        return result;
    }

    /**
     * Returns the handler chain (authentication and routing) built by the adapter, or
     * {@code null} when the adapter has no HTTP transport.
     */
    public Restlet getHandler() {
        return handler;
    }

    /**
     * Returns the full request paths that must reach this adapter even when it is mounted under
     * a prefix, such as RFC 9728 metadata under {@code /.well-known/}. Empty by default.
     */
    public List<String> getWellKnownPaths() {
        return List.of();
    }

    /**
     * Hands this adapter's transport to a listener shared with other adapters. The
     * {@link ServerHost} owns that server: it starts and stops it once for all its adapters.
     */
    void useSharedServer(Server shared) {
        this.server = shared;
        this.sharedServer = true;
    }

    public boolean isSharedServer() {
        return sharedServer;
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
        if (server != null && !sharedServer) {
            server.start();
        }
    }

    @Override
    public void stop() throws Exception {
        if (server != null && !sharedServer) {
            server.stop();
        }
    }

    /**
     * Limits how many requests reach {@code next} at once: past the limit, a request gets an
     * immediate 503 (#920). The REST and MCP adapters put it around their router, inside the
     * authentication chain, so a rejected login never takes a slot. The control port doesn't use
     * it, so health checks and metrics keep answering during an overload.
     */
    protected Restlet limitRequestsInProgress(Restlet next) {
        this.requestsInProgressLimit = maxRequestsInProgress();
        return new RequestsInProgressFilter(requestsInProgressLimit, getSpec().getType(),
                getSpec().getNamespace(), next);
    }

    /**
     * Limits a side endpoint of this adapter, such as the MCP server card, to {@code limit}
     * requests at once, apart from the adapter's own limit, so a flood of them cannot take the
     * slots of the adapter's main requests. Past it, a request gets an immediate 503.
     */
    protected Restlet limitSideRequestsInProgress(int limit, Restlet next) {
        return new RequestsInProgressFilter(limit, getSpec().getType(), getSpec().getNamespace(),
                next);
    }

    /** The requests in progress this adapter allows, or 0 when it has no limit (the control port). */
    int getRequestsInProgressLimit() {
        return requestsInProgressLimit;
    }

    /** The spec's maxRequestsInProgress, or {@value #DEFAULT_REQUESTS_IN_PROGRESS_PER_CPU} per CPU. */
    int maxRequestsInProgress() {
        Integer configured = getSpec().getMaxRequestsInProgress();
        return configured != null ? configured
                : DEFAULT_REQUESTS_IN_PROGRESS_PER_CPU * Runtime.getRuntime().availableProcessors();
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

        refuseAwsSigV4();

        if ("oauth2".equals(authentication.getType())
                && authentication instanceof OAuth2AuthenticationSpec oauth2) {
            return createOAuth2Restlet(oauth2, next);
        }

        ServerAuthenticationRestlet.requireCredentials(authentication,
                getCapability().getBindings(), describe());

        if ("basic".equals(authentication.getType())
                || "digest".equals(authentication.getType())) {
            return buildChallengeAuthenticator(authentication, next);
        }

        return new ServerAuthenticationRestlet(authentication, next, getCapability().getBindings());
    }

    /**
     * Refuses an {@code awsSigV4} authentication on this exposed adapter. It signs the calls a
     * capability makes; verifying them on an exposed adapter is not supported, so refuse it at
     * start rather than fail every request, or ignore it on a transport that has no
     * authentication chain, such as MCP stdio (#954).
     */
    protected void refuseAwsSigV4() {
        AuthenticationSpec authentication = getSpec().getAuthentication();
        if (authentication != null && "awsSigV4".equals(authentication.getType())) {
            throw new IllegalArgumentException("awsSigV4 authentication of " + describe()
                    + " is not supported: it only signs the calls to a consumed adapter.");
        }
    }

    /**
     * How errors name this adapter, such as "the rest adapter 'shop' on port 8080", or "the mcp
     * adapter 'tools'" when it has no port (stdio).
     */
    String describe() {
        String namespace = getSpec().getNamespace();
        int port = getSpec().getPort();
        return "the " + getSpec().getType() + " adapter"
                + (namespace != null ? " '" + namespace + "'" : "")
                + (port > 0 ? " on port " + port : "");
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

        // An unread request body is discarded by Restlet's Netty connector, which keeps the
        // connection alive when it can and otherwise announces its close (#878)
        ChallengeAuthenticator authenticator =
                new ChallengeAuthenticator(next.getContext(), false, scheme, "ikanos");
        authenticator.setVerifier(new SecretVerifier() {

            @Override
            public int verify(String identifier, char[] secret) {
                String expectedUsername = null;
                char[] expectedPassword = null;

                Map<String, Object> bindings = getCapability().getBindings();
                if (authentication instanceof BasicAuthenticationSpec basic) {
                    expectedUsername = Secrets.resolve(basic.getUsername(), bindings);
                    expectedPassword = Secrets.resolve(Secrets.password(basic.getPassword()),
                            bindings).toCharArray();
                } else if (authentication instanceof DigestAuthenticationSpec digest) {
                    expectedUsername = Secrets.resolve(digest.getUsername(), bindings);
                    expectedPassword = Secrets.resolve(Secrets.password(digest.getPassword()),
                            bindings).toCharArray();
                }

                if (expectedUsername == null || identifier == null || secret == null) {
                    return Verifier.RESULT_INVALID;
                }

                boolean usernameMatches = Secrets.matches(expectedUsername, identifier);
                boolean passwordMatches = Secrets.matches(expectedPassword, secret);

                return (usernameMatches && passwordMatches) ? Verifier.RESULT_VALID
                        : Verifier.RESULT_INVALID;
            }
        });
        authenticator.setNext(next);
        return authenticator;
    }

}