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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base Tunnel Configuration Specification Element.
 *
 * <p>Models the optional {@code tunnel:} block on a {@code ConsumesHttp} adapter
 * (see Ikanos JSON Schema definition {@code TunnelConfig}). The {@code type}
 * discriminator selects the concrete tunnel transport — {@code ziti} for the
 * embedded OpenZiti overlay today, with room for additional transports in
 * future versions without breaking existing capabilities.
 *
 * <p>The capability YAML is the single source of truth for tunnel configuration;
 * this class and its subtypes are the matching Jackson-deserialized representation.
 * Engine wiring (Jetty {@code SocketAddressResolver} + {@code ClientConnector}
 * overrides, OpenZiti SDK dial) is delivered in a later phase — this spec layer
 * lands ahead of it so capability authors can declare tunnels and have them
 * validated by the schema and Polychro rules today.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ZitiTunnelConfigSpec.class, name = "ziti")
})
public abstract class TunnelConfigSpec {

    private volatile String type;

    protected TunnelConfigSpec() {
        this(null);
    }

    protected TunnelConfigSpec(String type) {
        this.type = type;
    }

    public final String getType() {
        return type;
    }

    /**
     * Package-private setter used exclusively by Jackson during deserialization to
     * populate the {@code type} discriminator before the subtype is resolved. The
     * field is otherwise considered immutable once the concrete subtype is
     * constructed — exposing this setter publicly would allow callers to break
     * the {@link com.fasterxml.jackson.annotation.JsonTypeInfo} invariant by
     * mutating the discriminator independently of the runtime class.
     */
    void setType(String type) {
        this.type = type;
    }

}
