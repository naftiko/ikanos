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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import io.ikanos.durable.store.ConcurrentAppendException;

/**
 * One embedded H2 database holding every store of a capability.
 *
 * <p>A single connection serves all stores, guarded by a lock: each operation runs in its own
 * transaction, committed before it returns. The database is opened with {@code WRITE_DELAY=0},
 * so a commit has reached the file when it returns, and with H2's file lock, so a second process
 * cannot open the same file. Opening applies the forward-only schema migrations shipped in this
 * module, and refuses a database written by a newer schema or another minor version of H2.</p>
 */
final class JdbcDatabase implements AutoCloseable {

    /** The schema version this module writes; one script {@code V<n>__*.sql} per version. */
    static final int SCHEMA_VERSION = 1;

    private static final String[] MIGRATIONS = {"V1__stores.sql"};
    private static final String SCHEMA_KEY = "schema_version";
    private static final String H2_KEY = "h2_version";

    private final Path path;
    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    private JdbcDatabase(Path path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    /**
     * Opens, creating if needed, the H2 database at {@code path} ({@code .mv.db} is added by H2).
     *
     * @throws IllegalStateException when the file cannot be opened, holds a newer schema, or was
     *         written by another minor version of H2
     */
    static JdbcDatabase openH2(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the directory of the H2 store at "
                    + absolute + ": " + e.getMessage(), e);
        }
        String url = "jdbc:h2:file:" + absolute + ";WRITE_DELAY=0";
        Connection connection;
        try {
            connection = new org.h2.Driver().connect(url, new Properties());
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open the H2 store at " + absolute + ": "
                    + e.getMessage(), e);
        }
        JdbcDatabase database = new JdbcDatabase(absolute, connection);
        try {
            database.migrate();
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    /** The database file, without H2's extension. */
    Path path() {
        return path;
    }

    /** Work done in one transaction. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException, ConcurrentAppendException;
    }

    /**
     * Runs {@code work} in one transaction: committed when it returns, rolled back when it throws.
     * An unchecked exception and a {@link ConcurrentAppendException} pass through unchanged; an
     * {@link SQLException} becomes an {@link IllegalStateException} naming the store.
     */
    <T> T transact(Work<T> work) throws ConcurrentAppendException {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("The H2 store at " + path + " is closed");
            }
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                rollback();
                throw failure(e);
            } catch (ConcurrentAppendException | RuntimeException e) {
                rollback();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code work} in one transaction; for work that never reports a concurrent append. */
    <T> T call(Work<T> work) {
        try {
            return transact(work);
        } catch (ConcurrentAppendException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The value of an H2 setting, such as {@code WRITE_DELAY}. */
    String setting(String name) {
        return call(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT SETTING_VALUE FROM "
                    + "INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = ?")) {
                s.setString(1, name);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            connection.close();
        } catch (SQLException e) {
            throw failure(e);
        } finally {
            lock.unlock();
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            // The original failure is the one to report.
        }
    }

    private IllegalStateException failure(SQLException e) {
        return new IllegalStateException("H2 store at " + path + ": " + e.getMessage(), e);
    }

    // Migrations

    private void migrate() {
        call(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS store_meta (name VARCHAR(64) PRIMARY KEY, "
                        + "val VARCHAR(255) NOT NULL)");
            }
            String running = h2Version(c);
            int current = meta(c, SCHEMA_KEY).map(Integer::parseInt).orElse(0);
            if (current > SCHEMA_VERSION) {
                throw new IllegalStateException("The H2 store at " + path + " has schema version "
                        + current + ", newer than this Ikanos supports (" + SCHEMA_VERSION
                        + "): use the Ikanos version that wrote it");
            }
            Optional<String> recorded = meta(c, H2_KEY);
            if (recorded.isPresent() && !minor(recorded.get()).equals(minor(running))) {
                throw new IllegalStateException("The H2 store at " + path + " was written by H2 "
                        + recorded.get() + ", and this Ikanos runs H2 " + running
                        + ": another minor version may not read the file. Use the Ikanos "
                        + "version that wrote it, or export and re-create the store");
            }
            for (int version = current + 1; version <= SCHEMA_VERSION; version++) {
                runScript(c, MIGRATIONS[version - 1]);
            }
            putMeta(c, SCHEMA_KEY, Integer.toString(SCHEMA_VERSION));
            putMeta(c, H2_KEY, running);
            return null;
        });
    }

    private static void runScript(Connection c, String script) throws SQLException {
        String sql;
        try (InputStream in = JdbcDatabase.class.getResourceAsStream(script)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration script " + script);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration script " + script, e);
        }
        try (Statement s = c.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = stripComments(statement).trim();
                if (!trimmed.isEmpty()) {
                    s.execute(trimmed);
                }
            }
        }
    }

    private static String stripComments(String statement) {
        StringBuilder out = new StringBuilder();
        for (String line : statement.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String h2Version(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT H2VERSION()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    static String minor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static Optional<String> meta(Connection c, String name) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT val FROM store_meta WHERE name = ?")) {
            s.setString(1, name);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static void putMeta(Connection c, String name, String value) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "MERGE INTO store_meta (name, val) KEY (name) VALUES (?, ?)")) {
            s.setString(1, name);
            s.setString(2, value);
            s.executeUpdate();
        }
    }

    // Column helpers shared by the stores

    static void setInstant(PreparedStatement s, int index, Instant instant) throws SQLException {
        if (instant == null) {
            s.setObject(index, null);
        } else {
            s.setObject(index, instant.atOffset(ZoneOffset.UTC));
        }
    }

    static Instant getInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
