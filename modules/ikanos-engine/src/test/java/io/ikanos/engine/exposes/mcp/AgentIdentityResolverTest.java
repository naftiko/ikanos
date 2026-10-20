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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.data.Method;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.LogCapture;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

class AgentIdentityResolverTest {

    private static McpTrustSpec forwarded() {
        McpTrustSpec trust = new McpTrustSpec(McpTrustSpec.MODE_FORWARDED);
        trust.setAgentIdentityHeader("X-Agent-Id");
        trust.setTrustedNetworks(List.of("10.0.0.0/8"));
        return trust;
    }

    private static Request request(String peer) {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        request.getClientInfo().setAddress(peer);
        return request;
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
    void resolveShouldTrustIpv4MappedIpv6PeerInsideAnIpv4Network() {
        Request request = request("::ffff:10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        assertEquals("agent-42",
                new AgentIdentityResolver(forwarded()).resolve(request).orElseThrow().id());
    }

    @Test
    void resolveShouldIgnoreIdentityHeaderSentMoreThanOnce() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        request.getHeaders().add("X-Agent-Id", "admin");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty(),
                "Two identity headers mean the gateway did not overwrite the client's one");
    }

    @Test
    void resolveShouldIgnoreIdentityHeaderSentMoreThanOnceInAnyCase() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        request.getHeaders().add("x-agent-id", "admin");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
    }

    @Test
    void resolveShouldReturnEmptyWhenTrustedPeerSendsNoIdentity() {
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request("10.0.3.7")).isEmpty());
    }

    @Test
    void resolveShouldDropIdentityWithCarriageReturnLineFeed() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42\r\nX-Injected: forged");
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent 42", "agent\"42", "agent<42>", "ag\u00e9nt", "agent;42",
            "agent,admin", "agent\\42"})
    void resolveShouldDropIdentityWithCharactersOutsideTheAllowlist(String value) {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", value);
        assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty(),
                "Expected '" + value + "' to be rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spiffe://cluster.local/ns/support/sa/bot",
            "bot@support.example.com", "agent_42.v2", "a:b+c=d~e-f", "google-oauth2|1234567890"})
    void resolveShouldAcceptIdentityMadeOfAllowedCharacters(String value) {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", value);
        assertEquals(value,
                new AgentIdentityResolver(forwarded()).resolve(request).orElseThrow().id());
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
    void resolveShouldAcceptProviderPrefixedSubjectInVerifyMode() {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().subject("auth0|5f7c8ec7c33c6c004bbafe82").build());
        assertEquals("auth0|5f7c8ec7c33c6c004bbafe82",
                new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                        .resolve(request).orElseThrow().id());
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
    void resolveShouldRecordTokenActorInVerifyMode() {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().subject("agent-42")
                        .claim("act", java.util.Map.of("sub", "gateway-1")).build());
        AgentIdentity identity =
                new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                        .resolve(request).orElseThrow();
        assertEquals("agent-42", identity.id());
        assertEquals("gateway-1", identity.actor());
    }

    @Test
    void resolveShouldIgnoreActClaimThatIsNotAnObjectWithSubject() {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().subject("agent-42").claim("act", "gateway-1").build());
        assertNull(new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                .resolve(request).orElseThrow().actor());
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
    void sanitizeShouldKeepValueAtTheLengthLimit() {
        String value = "a".repeat(AgentIdentityResolver.MAX_IDENTITY_LENGTH);
        assertEquals(value, AgentIdentityResolver.sanitize(value));
    }

    @Test
    void resolveShouldWarnWithoutTheValueWhenAnIdentityIsIgnored() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent 42 secret-token");
        try (LogCapture logs = new LogCapture()) {
            assertTrue(new AgentIdentityResolver(forwarded()).resolve(request).isEmpty());
            List<String> warnings = ignoredIdentityWarnings(logs);
            assertEquals(1, warnings.size(), logs.messages().toString());
            assertTrue(warnings.get(0).contains("header X-Agent-Id (trust: forwarded)"),
                    warnings.get(0));
            assertTrue(warnings.get(0).contains("contains characters other than"), warnings.get(0));
            assertFalse(warnings.get(0).contains("secret-token"),
                    "The ignored value must never be logged: " + warnings.get(0));
        }
    }

    @Test
    void resolveShouldWarnWhenTheIdentityHeaderIsSentMoreThanOnce() {
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent-42");
        request.getHeaders().add("X-Agent-Id", "admin");
        try (LogCapture logs = new LogCapture()) {
            new AgentIdentityResolver(forwarded()).resolve(request);
            List<String> warnings = ignoredIdentityWarnings(logs);
            assertEquals(1, warnings.size(), logs.messages().toString());
            assertTrue(warnings.get(0).contains("sent 2 times"), warnings.get(0));
            assertFalse(warnings.get(0).contains("admin"), warnings.get(0));
        }
    }

    @Test
    void resolveShouldWarnWhenTheTokenSubjectIsIgnoredInVerifyMode() {
        Request request = request("85.12.4.9");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder()
                        .subject("a".repeat(AgentIdentityResolver.MAX_IDENTITY_LENGTH + 1))
                        .build());
        try (LogCapture logs = new LogCapture()) {
            assertTrue(new AgentIdentityResolver(new McpTrustSpec(McpTrustSpec.MODE_VERIFY))
                    .resolve(request).isEmpty());
            List<String> warnings = ignoredIdentityWarnings(logs);
            assertEquals(1, warnings.size(), logs.messages().toString());
            assertTrue(warnings.get(0).contains("token claim sub (trust: verify)"),
                    warnings.get(0));
            assertTrue(warnings.get(0).contains("longer than 256 characters"), warnings.get(0));
        }
    }

    @Test
    void resolveShouldNotWarnWhenNoIdentityIsSent() {
        Request blank = request("10.0.3.7");
        blank.getHeaders().add("X-Agent-Id", "  ");
        try (LogCapture logs = new LogCapture()) {
            AgentIdentityResolver resolver = new AgentIdentityResolver(forwarded());
            resolver.resolve(request("10.0.3.7"));
            resolver.resolve(blank);
            assertTrue(ignoredIdentityWarnings(logs).isEmpty(), logs.messages().toString());
        }
    }

    @Test
    void resolveShouldWarnAtMostOncePerIntervalAndCountTheOthers() {
        AtomicLong clock = new AtomicLong();
        AgentIdentityResolver resolver = new AgentIdentityResolver(forwarded(), clock::get);
        Request request = request("10.0.3.7");
        request.getHeaders().add("X-Agent-Id", "agent 42");
        try (LogCapture logs = new LogCapture()) {
            resolver.resolve(request);
            clock.set(1_000);
            resolver.resolve(request);
            resolver.resolve(request);
            assertEquals(1, ignoredIdentityWarnings(logs).size(), logs.messages().toString());

            clock.set(RateLimitedWarning.INTERVAL_MILLIS);
            resolver.resolve(request);
            List<String> warnings = ignoredIdentityWarnings(logs);
            assertEquals(2, warnings.size(), logs.messages().toString());
            assertTrue(warnings.get(1).contains("; 2 more ignored identities"), warnings.get(1));
        }
    }

    private static List<String> ignoredIdentityWarnings(LogCapture logs) {
        return logs.messages().stream().filter(m -> m.startsWith("Ignored agent identity"))
                .toList();
    }
}
