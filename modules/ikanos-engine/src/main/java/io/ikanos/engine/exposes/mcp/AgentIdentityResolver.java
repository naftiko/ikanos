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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.restlet.Context;
import org.restlet.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.engine.util.CidrRange;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

/**
 * Works out which agent sent an MCP request over Streamable HTTP, following the adapter's
 * {@code trust} block (issue #483).
 *
 * <ul>
 * <li>{@code none} (or no block): no identity.</li>
 * <li>{@code forwarded}: the {@code agentIdentityHeader} value, or the {@code sub} claim of the
 * base64 JSON in {@code claimsHeader}, only when the direct peer address
 * ({@code ClientInfo#getAddress()}, never {@code X-Forwarded-For}) is in
 * {@code trustedNetworks}. From any other peer the headers are ignored and a warning is
 * logged.</li>
 * <li>{@code verify}: the {@code sub} (else {@code client_id}) claim of the token that
 * {@link OAuth2AuthenticationRestlet} validated for this request.</li>
 * </ul>
 *
 * Resolved per request. The identity is for audit only: it never authorizes anything.
 */
public class AgentIdentityResolver {

    /** Longest identity recorded; longer values are dropped rather than truncated. */
    static final int MAX_IDENTITY_LENGTH = 256;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String mode;
    private final String identityHeader;
    private final String claimsHeader;
    private final List<CidrRange> trustedNetworks;

    /**
     * @param trust the adapter's trust block, or {@code null} for mode {@code none}.
     * @throws IllegalArgumentException when a trusted network is not a valid CIDR.
     */
    public AgentIdentityResolver(McpTrustSpec trust) {
        this.mode = trust != null ? trust.getMode() : McpTrustSpec.MODE_NONE;
        this.identityHeader = trust != null ? trust.getAgentIdentityHeader() : null;
        this.claimsHeader = trust != null ? trust.getClaimsHeader() : null;
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

    Optional<AgentIdentity> resolveForwarded(Request request) {
        String headerValue = header(request, identityHeader);
        String claimsValue = header(request, claimsHeader);
        if (headerValue == null && claimsValue == null) {
            return Optional.empty();
        }
        String peer = request.getClientInfo() != null ? request.getClientInfo().getAddress() : null;
        if (!isTrustedPeer(peer)) {
            // Header values are attacker-controlled here: log the peer, never the values.
            Context.getCurrentLogger().log(Level.WARNING,
                    "Ignoring forwarded agent identity from untrusted peer " + peer);
            return Optional.empty();
        }
        String id = sanitize(headerValue);
        if (id == null) {
            id = sanitize(subjectFromClaims(claimsValue));
        }
        return id != null ? Optional.of(new AgentIdentity(id, AgentIdentity.SOURCE_FORWARDED))
                : Optional.empty();
    }

    Optional<AgentIdentity> resolveVerified(Request request) {
        Object claims = request.getAttributes().get(
                OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE);
        if (!(claims instanceof JWTClaimsSet claimsSet)) {
            return Optional.empty();
        }
        String id = sanitize(claimsSet.getSubject());
        if (id == null) {
            Object clientId = claimsSet.getClaim("client_id");
            id = sanitize(clientId instanceof String value ? value : null);
        }
        return id != null ? Optional.of(new AgentIdentity(id, AgentIdentity.SOURCE_VERIFIED))
                : Optional.empty();
    }

    boolean isTrustedPeer(String peer) {
        for (CidrRange network : trustedNetworks) {
            if (network.contains(peer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the trimmed value, or {@code null} when blank, too long, or containing control
     *         characters (which could forge log lines).
     */
    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_IDENTITY_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return null;
            }
        }
        return trimmed;
    }

    /**
     * @return the {@code sub} of a base64 (standard or URL-safe) JSON claims object, or
     *         {@code null} when the value is missing or malformed.
     */
    static String subjectFromClaims(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String value = encoded.trim();
            byte[] decoded = value.indexOf('-') >= 0 || value.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(value)
                    : Base64.getDecoder().decode(value);
            JsonNode claims = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
            JsonNode sub = claims != null ? claims.get("sub") : null;
            return sub != null && sub.isTextual() ? sub.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(Request request, String name) {
        if (name == null || request.getHeaders() == null) {
            return null;
        }
        return request.getHeaders().getFirstValue(name, true);
    }
}
