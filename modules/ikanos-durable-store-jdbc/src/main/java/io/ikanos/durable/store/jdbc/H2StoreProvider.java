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
import java.util.Map;

import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

/**
 * The store of the {@code local} durability mode: every store in one embedded H2 file, which
 * survives a restart of the process. Discovered as store type {@code h2}.
 */
public final class H2StoreProvider implements StoreProvider {

    /** The store type, the value of {@code durability.store.type}. */
    public static final String TYPE = "h2";

    /** The setting holding the database file, from {@code durability.store.path}. */
    public static final String PATH = "path";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return "io.ikanos:ikanos-durable-store-jdbc";
    }

    /**
     * Opens the stores in the H2 file named by the {@code path} setting, creating it if needed.
     *
     * @throws IllegalArgumentException when {@code path} is missing
     * @throws IllegalStateException when the file cannot be opened or holds an incompatible store
     */
    @Override
    public Stores open(Map<String, String> settings) {
        String path = settings.get(PATH);
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("The h2 store needs a path: the file its "
                    + "executions are kept in (durability.store.path)");
        }
        return openStores(Path.of(path), Clock.systemUTC());
    }

    /** Opens the stores in the H2 file at {@code path}, reading store time from {@code clock}. */
    public static Stores openStores(Path path, Clock clock) {
        JdbcDatabase database = JdbcDatabase.openH2(path);
        return new Stores(new JdbcJournalStore(database), new JdbcExecutionIndex(database),
                new JdbcWorkQueue(database, clock), new JdbcSignalInbox(database),
                new JdbcBlobStore(database), database::close);
    }
}
