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
package io.ikanos.engine.consumes;

import java.util.Map;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Service-provider interface creating the runtime {@link ClientAdapter} for one {@code consumes}
 * {@code type}.
 *
 * <p>The built-in {@code http} factory is registered statically by
 * {@link ClientAdapterRegistry}. Optional adapters ship their own implementation, listed in
 * {@code META-INF/services/io.ikanos.engine.consumes.ClientAdapterFactory}, together with the
 * matching {@code io.ikanos.spec.consumes.ClientSpecType} on the spec side.</p>
 */
public interface ClientAdapterFactory {

    /** @return the {@code type} value this factory handles; never {@code null} */
    String type();

    /**
     * Create the adapter. Must not call {@link Capability#getClientAdapters()}: the adapter list is
     * not published until every adapter is constructed.
     *
     * @param capability the owning capability
     * @param spec       the deserialized consumes entry, of the class registered for
     *                   {@link #type()}
     * @param tunnels    started reverse-tunnel transports keyed by tunnel type; HTTP only, other
     *                   adapters may ignore it
     * @return the adapter
     */
    ClientAdapter create(Capability capability, ClientSpec spec, Map<String, Tunnel> tunnels);
}
