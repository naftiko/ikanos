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
package io.ikanos.engine.exposes.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.restlet.Context;
import org.restlet.Request;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.engine.util.CidrRange;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.node.ArrayNode;

/**
 * Works out which agent sent an MCP request over Streamable HTTP, following the adapter's
 * {@code trust} block (issue #483).
 *
 * <ul>
 * <li>{@code none} (or no block): no identity.</li>
 * <li>{@code forwarded}: the {@code agentIdentityHeader} value, only when the direct peer address
 * ({@code ClientInfo#getAddress()}, never {@code X-Forwarded-For}) is in
 * {@code trustedNetworks}. Over HTTP, {@link TrustedPeerFilter} has already refused any other
 * peer with 403; the check here keeps the resolver safe on its own.</li>
 * <li>{@code verify}: the {@code sub} (else {@code client_id}) claim of the token that
 * {@link OAuth2AuthenticationRestlet} validated for this request, plus the {@code sub} of its
 * RFC 8693 {@code act} claim when present.</li>
 * </ul>
 *
 * A value that is blank, longer than {@link #MAX_IDENTITY_LENGTH}, made of characters outside
 * {@link #IDENTITY_PATTERN}, or sent in more than one header is ignored rather than cleaned up.
 * Except for a blank value, ignoring one logs a {@link RateLimitedWarning} naming where it came
 * from and why, never the value itself, so a misconfigured gateway or identity provider can be
 * spotted. Resolved per request. The identity is for audit only: it never authorizes anything.
 */
public class AgentIdentityResolver {

    /** Longest identity recorded; longer values are dropped rather than truncated. */
    static final int MAX_IDENTITY_LENGTH = 256;

    /**
     * Characters an identity may contain: enough for ids, emails, SPIFFE URIs and provider-prefixed
     * subjects such as Auth0's {@code google-oauth2|123}, and nothing that can split a log line, a
     * header or a list (no whitespace, quotes, commas or semicolons).
     */
    static final Pattern IDENTITY_PATTERN = Pattern.compile("[A-Za-z0-9._~:@/+=|-]+");

    private final String mode;
    private final String identityHeader;
    private final List<CidrRange> trustedNetworks;
    private final RateLimitedWarning ignoredIdentityWarning;

    /**
     * @param trust the adapter's trust block, or {@code null} for mode {@code none}.
     * @throws IllegalArgumentException when a trusted network is not a valid CIDR.
     */
    public AgentIdentityResolver(McpTrustSpec trust) {
        this(trust, new RateLimitedWarning());
    }

    AgentIdentityResolver(McpTrustSpec trust, LongSupplier clock) {
        this(trust, new RateLimitedWarning(clock));
    }

    private AgentIdentityResolver(McpTrustSpec trust, RateLimitedWarning ignoredIdentityWarning) {
        this.ignoredIdentityWarning = ignoredIdentityWarning;
        this.mode = trust != null ? trust.getMode() : McpTrustSpec.MODE_NONE;
        this.identityHeader = trust != null ? trust.getAgentIdentityHeader() : null;
        List<CidrRange> networks = new ArrayList<>();
        if (trust != null && trust.getTrustedNetworks() != null) {
            for (String cidr : trust.getTrustedNetworks()) {
                try {
                    networks.add(CidrRange.parse(cidr));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid trust.trustedNetworks entry: " + e.getMessage(), e);
                }
            }
        }
        this.trustedNetworks = List.copyOf(networks);
    }

    /**
     * @return {@code true} in {@code forwarded} mode, where the gateway is the authentication
     *         boundary and untrusted peers must be refused.
     */
    boolean isForwarded() {
        return McpTrustSpec.MODE_FORWARDED.equals(mode);
    }

    /**
     * @param request the inbound HTTP request, or {@code null} on stdio.
     * @return the caller's identity, or empty when none can be trusted.
     */
    public Optional<AgentIdentity> resolve(Request request) {
        if (request == null) {
            return Optional.empty();
        }
        return switch (mode) {
            case McpTrustSpec.MODE_FORWARDED -> resolveForwarded(request);
            case McpTrustSpec.MODE_VERIFY -> resolveVerified(request);
            default -> Optional.empty();
        };
    }

    /**
     * The principal that owns the state a request creates and reads back, such as a task or a
     * multi round-trip {@code requestState}. Unlike {@link #resolve}, which serves the audit and
     * follows the {@code trust} mode, it never depends on that mode for an authenticated caller: a
     * request authenticated by OAuth2 is owned by its token's issuer and {@code sub}, or its issuer
     * and {@code client_id} for a token without one, whatever the mode. A token that names
     * neither is owned by the token itself, so only its holder reads the state back. Without a
     * token it is the identity a trusted gateway forwarded, or nobody in particular, as under
     * bearer and apikey authentication, whose one shared credential names no caller.
     *
     * <p>The owner is a JSON array of the adapter's namespace, the kind of principal and its
     * parts, so no two kinds, issuers or adapters can name the same owner: a {@code sub} of one
     * issuer never owns another's task, nor does a forwarded identity spelled like it.</p>
     *
     * @param namespace the namespace of the adapter serving the request
     * @param request the inbound HTTP request, or {@code null} on stdio
     * @param identity what {@link #resolve} returned for it
     */
    static String ownerOf(String namespace, Request request, Optional<AgentIdentity> identity) {
        Object claims = request != null
                ? request.getAttributes().get(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE)
                : null;
        if (claims instanceof JWTClaimsSet claimsSet) {
            String issuer = claimsSet.getIssuer();
            String subject = claimsSet.getSubject();
            if (subject != null && !subject.isBlank()) {
                return owner(namespace, "sub", issuer, subject);
            }
            Object clientId = claimsSet.getClaim("client_id");
            if (clientId instanceof String value && !value.isBlank()) {
                return owner(namespace, "client_id", issuer, value);
            }
            // Bound to the token itself: its signature sets it apart from any other token.
            Object token = request.getAttributes()
                    .get(OAuth2AuthenticationRestlet.VALIDATED_TOKEN_ATTRIBUTE);
            return owner(namespace, "token",
                    sha256(token instanceof String raw ? raw : claimsSet.toString()));
        }
        return identity.filter(id -> AgentIdentity.SOURCE_FORWARDED.equals(id.source()))
                .map(id -> owner(namespace, "forwarded", id.id()))
                .orElseGet(() -> anonymousOwner(namespace));
    }

    /** The owner of a request to the adapter {@code namespace} that names no caller. */
    static String anonymousOwner(String namespace) {
        return owner(namespace, McpCallContext.ANONYMOUS);
    }

    private static String owner(String namespace, String kind, String... parts) {
        ArrayNode owner = IkanosMappers.json().createArrayNode().add(namespace).add(kind);
        for (String part : parts) {
            owner.add(part);
        }
        return owner.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    Optional<AgentIdentity> resolveForwarded(Request request) {
        String peer = request.getClientInfo() != null ? request.getClientInfo().getAddress() : null;
        if (!isTrustedPeer(peer)) {
            return Optional.empty();
        }
        String id = accept(singleHeader(request, identityHeader), "header " + identityHeader);
        return id != null ? Optional.of(new AgentIdentity(id, AgentIdentity.SOURCE_FORWARDED))
                : Optional.empty();
    }

    Optional<AgentIdentity> resolveVerified(Request request) {
        Object claims = request.getAttributes().get(
                OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE);
        if (!(claims instanceof JWTClaimsSet claimsSet)) {
            return Optional.empty();
        }
        String id = accept(claimsSet.getSubject(), "token claim sub");
        if (id == null) {
            Object clientId = claimsSet.getClaim("client_id");
            id = accept(clientId instanceof String value ? value : null, "token claim client_id");
        }
        return id != null
                ? Optional.of(new AgentIdentity(id, AgentIdentity.SOURCE_VERIFIED,
                        actorOf(claimsSet)))
                : Optional.empty();
    }

    /**
     * @return {@code true} when {@code peer} is a literal address inside {@code trustedNetworks}.
     */
    boolean isTrustedPeer(String peer) {
        for (CidrRange network : trustedNetworks) {
            if (network.contains(peer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the trimmed value, or {@code null} when blank, too long, or containing a character
     *         outside {@link #IDENTITY_PATTERN} (control characters included, so it cannot forge
     *         log lines).
     */
    static String sanitize(String value) {
        if (value == null || value.trim().isEmpty() || rejectionOf(value) != null) {
            return null;
        }
        return value.trim();
    }

    /**
     * @return why {@code value} cannot be recorded, or {@code null} when it can, or when it is
     *         absent or blank (nothing was sent, so there is nothing to report).
     */
    static String rejectionOf(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_IDENTITY_LENGTH) {
            return "longer than " + MAX_IDENTITY_LENGTH + " characters";
        }
        if (!trimmed.isEmpty() && !IDENTITY_PATTERN.matcher(trimmed).matches()) {
            return "contains characters other than letters, digits and . _ ~ : @ / + = | -";
        }
        return null;
    }

    /**
     * @return the sanitized {@code value}, warning when a value was sent but has to be ignored.
     */
    private String accept(String value, String source) {
        String rejection = rejectionOf(value);
        if (rejection != null) {
            warnIgnored(source, rejection);
        }
        return sanitize(value);
    }

    /**
     * Logs why an identity was ignored, at most once per {@link RateLimitedWarning#INTERVAL_MILLIS}.
     * Never logs the value: in {@code forwarded} mode the agent may have written it.
     */
    private void warnIgnored(String source, String reason) {
        long dropped = ignoredIdentityWarning.acquire();
        if (dropped >= 0) {
            Context.getCurrentLogger().log(Level.WARNING,
                    "Ignored agent identity from " + source + " (trust: " + mode + "): " + reason
                            + "; " + dropped + " more ignored identities since the last warning");
        }
    }

    /**
     * @return the {@code sub} of the token's RFC 8693 {@code act} claim, or {@code null} when it
     *         is absent or not an object with a valid {@code sub}.
     */
    private String actorOf(JWTClaimsSet claims) {
        Object act = claims.getClaim("act");
        if (act instanceof Map<?, ?> actor && actor.get("sub") instanceof String sub) {
            return accept(sub, "token claim act.sub");
        }
        return null;
    }

    /**
     * @return the header's value, or {@code null} when it is absent or sent more than once (a
     *         second copy means the gateway passed the agent's own value through).
     */
    private String singleHeader(Request request, String name) {
        if (name == null || request.getHeaders() == null) {
            return null;
        }
        String[] values = request.getHeaders().getValuesArray(name, true);
        if (values.length > 1) {
            warnIgnored("header " + name, "sent " + values.length + " times");
        }
        return values.length == 1 ? values[0] : null;
    }
}
