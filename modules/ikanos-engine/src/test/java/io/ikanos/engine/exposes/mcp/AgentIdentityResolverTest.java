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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

class AgentIdentityResolverTest {

    private static McpTrustSpec forwarded() {
        McpTrustSpec trust = new McpTrustSpec(McpTrustSpec.MODE_FORWARDED);
        trust.setAgentIdentityHeader("X-Agent-Id");
        trust.setClaimsHeader("X-Forwarded-Claims");
        trust.setTrustedNetworks(List.of("10.0.0.0/8"));
        return trust;
    }

    private static Request request(String peer) {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        request.getClientInfo().setAddress(peer);
        return request;
    }

    private static String encodedClaims(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolveShouldReturnEmptyWhenTrustIsOmitted() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        assertTrue(new AgentIdentityResolver(null).resolve(request).isEmpty());
    }

    @Test
    void resolveShouldReturnEmptyForModeNone() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        assertTrue(new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_NONE))
                .resolve(request).isEmpty());
    }

    @Test
    void resolveShouldReturnEmptyWithoutHttpRequest() {
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(null).isEmpty());
    }

    @Test
    void resolveShouldReadHeaderFromTrustedPeer() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        Optional<AgentIdentity> identity = new AgentIdentityResolver(forwarded()).resolve(request);
        assertEquals(new AgentIdentity("agent-42", AgentIdentity.SOURCE_FORWARDED),
                identity.orElseThrow());
    }

    @Test
    void resolveShouldIgnoreHeaderFromUntrustedPeer() {
        Request request = request("85.12.4.9");
        request.getHeaders().add("X-Agent-Id", "admin");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
    }

    @Test
    void resolveShouldNotTrustXForwardedForOverTheRealPeer() {
        Request request = request("85.12.4.9");
        request.getHeaders().add("X-Forwarded-For", "10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "admin");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
    }

    @Test
    void resolveShouldFallBackToClaimsSubjectWhenHeaderIsAbsent() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Forwarded-Claims",
                encodedClaims("{\"sub\":\"agent-7\",\"team\":\"support\"}"));
        assertEquals("agent-7",
                new AgentIdentityResolver(forwarded()).resolve(request).orElseThrow().id());
    }

    @Test
    void resolveShouldPreferIdentityHeaderOverClaims() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        request.getHeaders().add("X-Forwarded-Claims", encodedClaims("{\"sub\":\"agent-7\"}"));
        assertEquals("agent-42",
                new AgentIdentityResolver(forwarded()).resolve(request).orElseThrow().id());
    }

    @Test
    void resolveShouldReturnEmptyWhenTrustedPeerSendsNoIdentity() {
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request("10.0.3.7")).isEmpty());
    }

    @Test
    void resolveShouldDropIdentityWithControlCharacters() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42\nFAKE LOG LINE");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
    }

    @Test
    void resolveShouldReadSubjectOfValidatedTokenInVerifyMode() throws Exception {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().subject("agent-42").build());
        Optional<AgentIdentity> identity =
                new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                        .resolve(request);
        assertEquals(new AgentIdentity("agent-42", AgentIdentity.SOURCE_VERIFIED),
                identity.orElseThrow());
    }

    @Test
    void resolveShouldFallBackToClientIdInVerifyMode() {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().claim("client_id", "support-bot").build());
        assertEquals("support-bot",
                new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                        .resolve(request).orElseThrow().id());
    }

    @Test
    void resolveShouldIgnoreHeadersInVerifyMode() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        assertTrue(new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                .resolve(request).isEmpty());
    }

    @Test
    void constructorShouldRejectInvalidTrustedNetwork() {
        McpTrustSpec trust = forwarded();
        trust.setTrustedNetworks(List.of("010.0.0.0/8"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AgentIdentityResolver(trust));
        assertTrue(error.getMessage().contains("trust.trustedNetworks"), error.getMessage());
    }

    @Test
    void sanitizeShouldDropOverlongValues() {
        assertNull(AgentIdentityResolver.sanitize(
                "a".repeat(AgentIdentityResolver.MAX_IDENTITY_LENGTH + 1)));
    }

    @Test
    void subjectFromClaimsShouldReturnNullOnMalformedPayload() {
        assertNull(AgentIdentityResolver.subjectFromClaims("%%%not-base64%%%"));
        assertNull(AgentIdentityResolver.subjectFromClaims(encodedClaims("[1,2,3]")));
        assertNull(AgentIdentityResolver.subjectFromClaims(encodedClaims("{\"sub\":42}")));
    }
}
