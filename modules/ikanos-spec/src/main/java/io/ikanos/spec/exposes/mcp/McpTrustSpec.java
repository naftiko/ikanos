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
package io.ikanos.spec.exposes.mcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Trust Specification Element.
 *
 * Says how an HTTP MCP adapter learns which agent is calling it, so the identity can be recorded
 * on traces and logs. When the block is omitted, the mode is {@code none}.
 *
 * <ul>
 * <li>{@code forwarded}: read the identity from {@code agentIdentityHeader} (or the {@code sub}
 * claim of {@code claimsHeader}), only when the direct peer address is in
 * {@code trustedNetworks}.</li>
 * <li>{@code verify}: take the identity from the token validated by the adapter's
 * {@code authentication: oauth2} block.</li>
 * <li>{@code none}: do not record an agent identity.</li>
 * </ul>
 *
 * The identity is used for audit only: it never authorizes a request and is never forwarded to
 * consumed APIs.
 */
public class McpTrustSpec {

    public static final String MODE_FORWARDED = "forwarded";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_NONE = "none";

    private final AtomicReference<String> mode = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<String> agentIdentityHeader = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<List<String>> trustedNetworks = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<String> claimsHeader = new AtomicReference<>();

    public McpTrustSpec() {}

    public McpTrustSpec(String mode) {
        this.mode.set(mode);
    }

    /**
     * @return the declared mode, or {@code none} when the block declares none.
     */
    public String getMode() {
        String value = mode.get();
        return value != null ? value : MODE_NONE;
    }

    public void setMode(String mode) {
        this.mode.set(mode);
    }

    public String getAgentIdentityHeader() {
        return agentIdentityHeader.get();
    }

    public void setAgentIdentityHeader(String agentIdentityHeader) {
        this.agentIdentityHeader.set(agentIdentityHeader);
    }

    public List<String> getTrustedNetworks() {
        return trustedNetworks.get();
    }

    public void setTrustedNetworks(List<String> trustedNetworks) {
        this.trustedNetworks.set(trustedNetworks != null ? List.copyOf(trustedNetworks) : null);
    }

    public String getClaimsHeader() {
        return claimsHeader.get();
    }

    public void setClaimsHeader(String claimsHeader) {
        this.claimsHeader.set(claimsHeader);
    }
}
