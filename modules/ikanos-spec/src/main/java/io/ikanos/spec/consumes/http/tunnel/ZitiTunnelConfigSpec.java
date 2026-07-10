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
package io.ikanos.spec.consumes.http.tunnel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenZiti tunnel transport specification.
 *
 * <p>Mirrors the {@code TunnelZiti} JSON Schema definition. When a
 * {@code ConsumesHttp} adapter declares a {@code tunnel:} block with
 * {@code type: ziti}, requests are dialed through the configured Ziti service
 * instead of the public network. Identity authentication, service authorization,
 * and end-to-end mTLS are handled by the Ziti overlay; application-layer
 * authentication declared via the {@code authentication} block runs unchanged
 * on top.
 *
 * <h2>Recommended usage</h2>
 *
 * The {@link #identity} field should reference a binding via a Mustache
 * expression (for example {@code {{secrets.ZITI_IDENTITY}}}) rather than an
 * inline filesystem path; the Polychro rule
 * {@code ikanos-tunnel-identity-must-bind} enforces this at lint time.
 *
 * <h2>Fallback semantics</h2>
 *
 * The {@link #fallback} field controls behavior when the tunnel cannot be
 * established:
 * <ul>
 *   <li>{@code fail} (default) — operations return a 503-equivalent error.</li>
 *   <li>{@code direct} — engine attempts a direct HTTP call to {@code baseUri}.
 *       Intended for local development against a stubbed upstream; the rule
 *       {@code ikanos-tunnel-fallback-direct-warns} warns when this is used
 *       against a non-loopback baseUri.</li>
 * </ul>
 */
public class ZitiTunnelConfigSpec extends TunnelConfigSpec {

    /** Default fallback behavior matching the {@code TunnelZiti.fallback} schema default. */
    public static final String FALLBACK_FAIL = "fail";

    /** Alternative fallback that attempts a direct dial; discouraged in production. */
    public static final String FALLBACK_DIRECT = "direct";

    private volatile String service;
    private volatile String identity;
    private volatile String fallback;

    public ZitiTunnelConfigSpec() {
        this(null, null, null);
    }

    public ZitiTunnelConfigSpec(String service, String identity) {
        this(service, identity, null);
    }

    public ZitiTunnelConfigSpec(String service, String identity, String fallback) {
        super("ziti");
        this.service = service;
        this.identity = identity;
        this.fallback = fallback;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getFallback() {
        return fallback;
    }

    public void setFallback(String fallback) {
        this.fallback = fallback;
    }

}
