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
package io.ikanos.spec.consumes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;

/**
 * Registry of {@code consumes} types known to the spec module.
 *
 * <p>{@code http} and {@code mcp} are core types, registered statically so that the native CLI
 * never depends on service discovery to load them. Additional types are discovered once, lazily,
 * through {@link ClientSpecType} providers. A provider that claims an already registered type is
 * rejected.</p>
 */
public final class ClientSpecTypes {

    /** The built-in consumed HTTP type. */
    public static final String HTTP = "http";

    /** The built-in consumed MCP type. */
    public static final String MCP = McpClientSpec.TYPE;

    private static volatile Map<String, Class<? extends ClientSpec>> registry;

    private ClientSpecTypes() {
    }

    /**
     * Resolve the spec class for a {@code type} value.
     *
     * @param type the {@code type} value; {@code null} resolves to {@link #HTTP}
     * @return the spec class, or {@code null} when the type is not registered
     */
    public static Class<? extends ClientSpec> resolve(String type) {
        return registry().get(type == null ? HTTP : type);
    }

    /** @return the registered type names, in registration order */
    public static Set<String> registeredTypes() {
        return registry().keySet();
    }

    private static Map<String, Class<? extends ClientSpec>> registry() {
        Map<String, Class<? extends ClientSpec>> current = registry;
        if (current == null) {
            synchronized (ClientSpecTypes.class) {
                current = registry;
                if (current == null) {
                    current = load(ServiceLoader.load(ClientSpecType.class,
                            ClientSpecType.class.getClassLoader()));
                    registry = current;
                }
            }
        }
        return current;
    }

    /**
     * Build a registry from the built-in type plus the given providers. Package-private for tests.
     *
     * @throws IllegalStateException when two providers claim the same type
     */
    static Map<String, Class<? extends ClientSpec>> load(Iterable<ClientSpecType> providers) {
        Map<String, Class<? extends ClientSpec>> map = new LinkedHashMap<>();
        map.put(HTTP, HttpClientSpec.class);
        map.put(MCP, McpClientSpec.class);
        for (ClientSpecType provider : providers) {
            String type = provider.type();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(
                        "ClientSpecType provider " + provider.getClass().getName()
                                + " declares no type");
            }
            if (map.containsKey(type)) {
                throw new IllegalStateException("Duplicate consumes type '" + type
                        + "' registered by " + provider.getClass().getName());
            }
            map.put(type, provider.specClass());
        }
        return Collections.unmodifiableMap(map);
    }
}
