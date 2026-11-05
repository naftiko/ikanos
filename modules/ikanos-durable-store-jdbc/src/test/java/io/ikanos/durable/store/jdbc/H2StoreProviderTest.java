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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

class H2StoreProviderTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("data").resolve("durable");
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = new org.h2.Driver().connect("jdbc:h2:file:" + file().toAbsolutePath(),
                new Properties()); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void providerShouldBeDiscoveredAsTheH2Store() {
        StoreProvider provider = StoreProvider.find("h2").orElseThrow();

        assertEquals("io.ikanos:ikanos-durable-store-jdbc", provider.module());
        assertTrue(StoreProvider.available().contains("h2"));
        try (Stores stores = provider.open(Map.of(H2StoreProvider.PATH, file().toString()))) {
            assertTrue(stores.journal().read(new ExecutionId("x")).isEmpty());
        }
        assertTrue(Files.exists(directory.resolve("data").resolve("durable.mv.db")),
                "The database file is created, with its parent directory");
    }

    @Test
    void providerShouldRequireAPath() {
        H2StoreProvider provider = new H2StoreProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.open(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> provider.open(Map.of(H2StoreProvider.PATH, " ")));
    }

    @Test
    void databaseShouldCommitWithoutWriteDelay() {
        try (JdbcDatabase database = JdbcDatabase.openH2(file())) {
            assertEquals("0", database.setting("WRITE_DELAY"));
            assertEquals(file().toAbsolutePath().normalize(), database.path());
        }
    }

    @Test
    void databaseShouldRefuseANewerSchema() throws SQLException {
        JdbcDatabase.openH2(file()).close();
        execute("UPDATE store_meta SET val = '99' WHERE name = 'schema_version'");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JdbcDatabase.openH2(file()));
        assertTrue(e.getMessage().contains("schema version 99"), e.getMessage());
    }

    @Test
    void databaseShouldRefuseAFileFromAnotherMinorH2Version() throws SQLException {
        JdbcDatabase.openH2(file()).close();
        execute("UPDATE store_meta SET val = '2.1.214' WHERE name = 'h2_version'");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JdbcDatabase.openH2(file()));
        assertTrue(e.getMessage().contains("H2 2.1.214"), e.getMessage());
    }

    @Test
    void databaseShouldAcceptAFileFromAnotherPatchVersion() throws SQLException {
        JdbcDatabase.openH2(file()).close();
        String minor;
        try (JdbcDatabase database = JdbcDatabase.openH2(file())) {
            minor = JdbcDatabase.minor(database.call(c -> {
                try (Statement s = c.createStatement();
                        var rs = s.executeQuery("SELECT H2VERSION()")) {
                    rs.next();
                    return rs.getString(1);
                }
            }));
        }
        execute("UPDATE store_meta SET val = '" + minor + ".1' WHERE name = 'h2_version'");

        JdbcDatabase.openH2(file()).close();
    }

    @Test
    void databaseShouldRefuseAPathItCannotCreate() throws Exception {
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "a file, not a directory");

        assertThrows(IllegalStateException.class,
                () -> JdbcDatabase.openH2(blocker.resolve("durable")));
    }

    @Test
    void storesShouldRefuseWorkOnceClosedAndTolerateASecondClose() {
        Stores stores = H2StoreProvider.openStores(file(), Clock.systemUTC());
        stores.close();
        stores.close();

        assertThrows(IllegalStateException.class,
                () -> stores.journal().read(new ExecutionId("x")));
    }

    @Test
    void sqlFailuresShouldNameTheStore() {
        try (JdbcDatabase database = JdbcDatabase.openH2(file())) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> database.call(c -> {
                        try (Statement s = c.createStatement()) {
                            return s.executeQuery("SELECT * FROM no_such_table");
                        }
                    }));
            assertTrue(e.getMessage().startsWith("H2 store at "), e.getMessage());
        }
    }

    @Test
    void aFailedTransactionShouldLeaveNothingBehind() throws Exception {
        ExecutionId id = new ExecutionId("exec-1");
        try (Stores stores = H2StoreProvider.openStores(file(), Clock.systemUTC())) {
            assertThrows(IllegalArgumentException.class, () -> stores.journal().append(id, 0, 0,
                    List.of(new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, null, null),
                            new JournalEntry(3, JournalEntry.Kind.ACTION_SUCCEEDED, null, null))));
            stores.journal().append(id, 0, 0,
                    List.of(new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, null, null)));
            assertEquals(1, stores.journal().read(id).size());
        }
    }

    @Test
    void duplicateKeysShouldBeRecognisedThroughABatchFailure() {
        SQLException duplicate = new SQLException("dup", "23505");
        BatchUpdateException batch = new BatchUpdateException("batch", "XX000", 0, new int[0],
                duplicate);
        SQLException chained = new SQLException("outer", "XX000");
        chained.setNextException(new SQLException("inner", "23505"));

        assertTrue(JdbcJournalStore.isDuplicateKey(duplicate));
        assertTrue(JdbcJournalStore.isDuplicateKey(batch));
        assertTrue(JdbcJournalStore.isDuplicateKey(chained));
        assertFalse(JdbcJournalStore.isDuplicateKey(new SQLException("other", "42000")));
    }

    @Test
    void minorShouldKeepMajorAndMinor() {
        assertEquals("2.3", JdbcDatabase.minor("2.3.232"));
        assertEquals("2", JdbcDatabase.minor("2"));
    }
}
