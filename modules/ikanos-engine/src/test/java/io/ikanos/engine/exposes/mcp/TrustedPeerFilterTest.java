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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

/**
 * In {@code forwarded} mode the gateway is the authentication boundary, so a peer outside
 * {@code trustedNetworks} is refused before any MCP handling (issue #483).
 */
class TrustedPeerFilterTest {

    private final AtomicBoolean nextCalled = new AtomicBoolean();
    private final AtomicLong clock = new AtomicLong();

    private TrustedPeerFilter filter() {
        McpTrustSpec trust = new McpTrustSpec(McpTrustSpec.MODE_FORWARDED);
        trust.setAgentIdentityHeader("X-Agent-Id");
        trust.setTrustedNetworks(List.of("10.0.0.0/8"));
        Restlet next = new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                nextCalled.set(true);
                response.setStatus(Status.SUCCESS_OK);
            }
        };
        return new TrustedPeerFilter(new Context(), new AgentIdentityResolver(trust), next,
                clock::get);
    }

    private static Response send(TrustedPeerFilter filter, String peer) {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        request.getClientInfo().setAddress(peer);
        Response response = new Response(request);
        filter.handle(request, response);
        return response;
    }

    @Test
    void handleShouldAnswerForbiddenToUntrustedPeerWithoutCallingNext() {
        Response response = send(filter(), "85.12.4.9");
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
        assertFalse(nextCalled.get(), "An untrusted peer must not reach MCP handling");
    }

    @Test
    void handleShouldRefuseUntrustedPeerEvenWhenItSendsAnIdentityHeader() {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        request.getClientInfo().setAddress("85.12.4.9");
        request.getHeaders().add("X-Agent-Id", "admin");
        Response response = new Response(request);
        filter().handle(request, response);
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
        assertFalse(nextCalled.get());
    }

    @Test
    void handleShouldNotRevealTrustedNetworksOrPeerAddressInTheBody() throws Exception {
        String body = send(filter(), "85.12.4.9").getEntity().getText();
        assertFalse(body.contains("10.0.0.0"), body);
        assertFalse(body.contains("85.12.4.9"), body);
    }

    @Test
    void handleShouldTreatMissingPeerAddressAsUntrusted() {
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, send(filter(), null).getStatus());
        assertFalse(nextCalled.get());
    }

    @Test
    void handleShouldPassTrustedPeerToNext() {
        Response response = send(filter(), "10.0.3.7");
        assertTrue(nextCalled.get());
        assertEquals(Status.SUCCESS_OK, response.getStatus());
    }

    @Test
    void handleShouldPassIpv4MappedIpv6PeerInsideATrustedNetwork() {
        send(filter(), "::ffff:10.0.3.7");
        assertTrue(nextCalled.get());
    }

    @Test
    void shouldWarnShouldAllowOneWarningPerInterval() {
        TrustedPeerFilter filter = filter();
        assertTrue(filter.shouldWarn(0));
        assertFalse(filter.shouldWarn(1_000));
        assertFalse(filter.shouldWarn(TrustedPeerFilter.WARNING_INTERVAL_MILLIS - 1));
        assertTrue(filter.shouldWarn(TrustedPeerFilter.WARNING_INTERVAL_MILLIS));
    }

    @Test
    void handleShouldCountRefusalsWhoseWarningWasSuppressed() {
        TrustedPeerFilter filter = filter();
        send(filter, "85.12.4.9");
        clock.set(1_000);
        send(filter, "85.12.4.9");
        send(filter, "85.12.4.10");
        assertEquals(2, filter.suppressedWarnings());
    }
}
