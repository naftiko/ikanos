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

import java.time.Clock;
import java.util.Map;

import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

/**
 * The store of the {@code distributed} durability mode: every store in one PostgreSQL database
 * that several replicas share. Discovered as store type {@code postgres}. Its journal declares
 * {@link io.ikanos.durable.store.StoreFeature#SHARED}.
 */
public final class PostgresStoreProvider implements StoreProvider {

    /** The store type, the value of {@code durability.store.type}. */
    public static final String TYPE = "postgres";

    /** The setting holding the JDBC URL, from {@code durability.store.url}. */
    public static final String URL = "url";

    /** The setting holding the user, from {@code durability.store.user}. */
    public static final String USER = "user";

    /** The setting holding the password, from {@code durability.store.password}. */
    public static final String PASSWORD = "password";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return "io.ikanos:ikanos-durable-store-jdbc";
    }

    /**
     * Opens the stores in the PostgreSQL database named by the {@code url} setting, creating its
     * tables if needed.
     *
     * @throws IllegalArgumentException when {@code url} is missing or not a PostgreSQL JDBC URL
     * @throws IllegalStateException when the database cannot be reached or holds a newer schema
     */
    @Override
    public Stores open(Map<String, String> settings) {
        String url = settings.get(URL);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The postgres store needs a url: the JDBC URL of "
                    + "the database its executions are kept in (durability.store.url)");
        }
        return openStores(url, settings.get(USER), settings.get(PASSWORD), Clock.systemUTC());
    }

    /** Opens the stores in the PostgreSQL database at {@code url}, reading time from {@code clock}. */
    public static Stores openStores(String url, String user, String password, Clock clock) {
        JdbcDatabase database = JdbcDatabase.openPostgres(url, user, password,
                JdbcDatabase.DEFAULT_POOL_SIZE);
        return new Stores(new JdbcJournalStore(database), new JdbcExecutionIndex(database),
                new JdbcWorkQueue(database, clock), new JdbcSignalInbox(database),
                new JdbcBlobStore(database), database::close);
    }
}
