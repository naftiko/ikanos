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
package io.ikanos.durable.store.jdbc;

import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.io.TempDir;

import io.ikanos.durable.store.StoreConformanceTest;
import io.ikanos.durable.store.Stores;

/** The H2 store against the store conformance suite, including a real close and reopen. */
class H2StoresTest extends StoreConformanceTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("durable");
    }

    @Override
    protected Stores open(Clock clock) {
        return H2StoreProvider.openStores(file(), clock);
    }

    @Override
    protected Stores reopen(Stores stores, Clock clock) {
        stores.close();
        return H2StoreProvider.openStores(file(), clock);
    }
}
