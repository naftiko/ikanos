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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.engine.consumes.mcp.McpClientAdapter;
import io.ikanos.engine.consumes.sql.SqlClientAdapter;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;

/**
 * Registry of {@link ClientAdapterFactory} instances, keyed by {@code consumes} {@code type}.
 *
 * <p>Replaces the literal {@code "http".equals(type)} branch that used to live in
 * {@link Capability}. The core {@code http}, {@code mcp} and {@code sql} factories are registered
 * statically, so they never depend on service discovery. Optional factories are
 * discovered once with {@link ServiceLoader}, following the {@code ikanos-tunnel-ziti} precedent.
 * </p>
 */
public final class ClientAdapterRegistry {

    /** Built-in factory for {@code type: http}. */
    static final ClientAdapterFactory HTTP = new ClientAdapterFactory() {
        @Override
        public String type() {
            return "http";
        }

        @Override
        public ClientAdapter create(Capability capability, ClientSpec spec,
                Map<String, Tunnel> tunnels) {
            return new HttpClientAdapter(capability, (HttpClientSpec) spec, tunnels);
        }
    };

    /** Built-in factory for {@code type: mcp}. */
    static final ClientAdapterFactory MCP = new ClientAdapterFactory() {
        @Override
        public String type() {
            return McpClientSpec.TYPE;
        }

        @Override
        public ClientAdapter create(Capability capability, ClientSpec spec,
                Map<String, Tunnel> tunnels) {
            return new McpClientAdapter(capability, (McpClientSpec) spec);
        }
    };

    /** Built-in factory for {@code type: sql}. Tunnels do not apply to JDBC connections. */
    static final ClientAdapterFactory SQL = new ClientAdapterFactory() {
        @Override
        public String type() {
            return SqlClientSpec.TYPE;
        }

        @Override
        public ClientAdapter create(Capability capability, ClientSpec spec,
                Map<String, Tunnel> tunnels) {
            return new SqlClientAdapter(capability, (SqlClientSpec) spec);
        }
    };

    private static volatile ClientAdapterRegistry defaultRegistry;

    private final Map<String, ClientAdapterFactory> factories;

    ClientAdapterRegistry(Iterable<ClientAdapterFactory> discovered) {
        Map<String, ClientAdapterFactory> map = new LinkedHashMap<>();
        map.put(HTTP.type(), HTTP);
        map.put(MCP.type(), MCP);
        map.put(SQL.type(), SQL);
        for (ClientAdapterFactory factory : discovered) {
            String type = factory.type();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("ClientAdapterFactory "
                        + factory.getClass().getName() + " declares no type");
            }
            if (map.containsKey(type)) {
                throw new IllegalStateException("Duplicate consumes adapter type '" + type
                        + "' registered by " + factory.getClass().getName());
            }
            map.put(type, factory);
        }
        this.factories = Collections.unmodifiableMap(map);
    }

    /** @return the registry built from the built-in factory plus discovered providers */
    public static ClientAdapterRegistry getDefault() {
        ClientAdapterRegistry current = defaultRegistry;
        if (current == null) {
            synchronized (ClientAdapterRegistry.class) {
                current = defaultRegistry;
                if (current == null) {
                    current = new ClientAdapterRegistry(ServiceLoader.load(
                            ClientAdapterFactory.class,
                            ClientAdapterFactory.class.getClassLoader()));
                    defaultRegistry = current;
                }
            }
        }
        return current;
    }

    /** @return the registered type names, in registration order */
    public Set<String> registeredTypes() {
        return factories.keySet();
    }

    /**
     * Create the adapter for one consumes entry.
     *
     * @throws IllegalArgumentException when no factory is registered for the entry's type
     */
    public ClientAdapter create(Capability capability, ClientSpec spec,
            Map<String, Tunnel> tunnels) {
        String type = spec.getType() != null ? spec.getType() : HTTP.type();
        ClientAdapterFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("No client adapter registered for consumes type '"
                    + type + "' (namespace '" + spec.getNamespace() + "'). Registered types: "
                    + factories.keySet());
        }
        return factory.create(capability, spec, tunnels);
    }
}
