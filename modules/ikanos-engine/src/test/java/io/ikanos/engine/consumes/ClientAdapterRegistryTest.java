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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

class ClientAdapterRegistryTest {

    private static ClientAdapterFactory factory(String type) {
        return new ClientAdapterFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public ClientAdapter create(Capability capability, ClientSpec spec,
                    Map<String, Tunnel> tunnels) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void registryShouldRegisterBuiltInHttpFirst() {
        ClientAdapterRegistry registry = new ClientAdapterRegistry(List.of(factory("other")));

        assertEquals(List.of("http", "other"), List.copyOf(registry.registeredTypes()));
    }

    @Test
    void registryShouldRejectDuplicateType() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ClientAdapterRegistry(List.of(factory("http"))));

        assertTrue(error.getMessage().contains("Duplicate consumes adapter type 'http'"));
    }

    @Test
    void createShouldBuildHttpAdapterForHttpSpec() {
        ClientAdapterRegistry registry = new ClientAdapterRegistry(List.of());

        ClientAdapter adapter = registry.create(null,
                new HttpClientSpec("svc", "https://api.example.com", null), Map.of());

        assertInstanceOf(HttpClientAdapter.class, adapter);
        assertEquals("svc", adapter.getNamespace());
    }

    @Test
    void createShouldFailForUnregisteredTypeNamingIt() {
        ClientAdapterRegistry registry = new ClientAdapterRegistry(List.of());
        HttpClientSpec spec = new HttpClientSpec("svc", "https://api.example.com", null);
        spec.setType("carrier-pigeon");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.create(null, spec, Map.of()));

        assertTrue(error.getMessage().contains("'carrier-pigeon'"), error.getMessage());
    }

    @Test
    void outcomeShouldMapHttpStatusRanges() {
        assertEquals(Outcome.SUCCESS, Outcome.fromHttpStatus(204));
        assertEquals(Outcome.CLIENT_ERROR, Outcome.fromHttpStatus(404));
        assertEquals(Outcome.UPSTREAM_ERROR, Outcome.fromHttpStatus(503));
    }
}
