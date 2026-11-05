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
package io.ikanos.durable.store.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.durable.store.StoreConformanceTest;
import io.ikanos.durable.store.StoreFeature;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

/** The memory store against the store conformance suite. */
public class MemoryStoresTest extends StoreConformanceTest {

    @Override
    protected Stores open(Clock clock) {
        return MemoryStoreProvider.openStores(clock);
    }

    @Test
    void providerShouldBeDiscoveredAsTheMemoryStore() {
        StoreProvider provider = StoreProvider.find("memory").orElseThrow();
        assertEquals("io.ikanos:ikanos-durable-store-memory", provider.module());
        assertTrue(StoreProvider.available().contains("memory"));
        assertTrue(provider.open(Map.of()).journal().read(ID).isEmpty());
    }

    @Test
    void memoryStoreShouldNotClaimToSurviveARestart() {
        assertFalse(stores.journal().features().contains(StoreFeature.DURABLE));
    }
}
