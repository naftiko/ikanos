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

import java.time.Clock;
import java.util.Map;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

/**
 * Provider of the {@code memory} store: everything in process memory, lost when the process
 * stops. For the {@code memory} durability mode, development and tests.
 */
public final class MemoryStoreProvider implements StoreProvider {

    public static final String TYPE = "memory";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return "io.ikanos:ikanos-durable-store-memory";
    }

    @Override
    public Stores open(Map<String, String> settings) {
        return openStores(Clock.systemUTC());
    }

    /** Opens a fresh set of memory stores reading time from {@code clock}. */
    public static Stores openStores(Clock clock) {
        return new Stores(new MemoryJournalStore(), new MemoryExecutionIndex(),
                new MemoryWorkQueue(clock), new MemorySignalInbox(), new MemoryBlobStore());
    }
}
