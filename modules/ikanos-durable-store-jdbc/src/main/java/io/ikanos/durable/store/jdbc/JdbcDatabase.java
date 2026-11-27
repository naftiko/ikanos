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
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.ikanos.durable.store.ConcurrentAppendException;

/**
 * One database holding every store of a capability: an embedded H2 file for the {@code local}
 * mode, or a PostgreSQL database that several replicas share for the {@code distributed} mode.
 *
 * <p>Each operation runs in its own transaction, committed before it returns.</p>
 *
 * <p><b>H2.</b> A single connection serves all stores, guarded by a lock. The database is opened
 * with {@code WRITE_DELAY=0}, so a commit has reached the file when it returns, with H2's file
 * lock, so a second process cannot open the same file, and without H2's shutdown hook
 * ({@code DB_CLOSE_ON_EXIT=FALSE}), so the file stays open until its owner closes it after
 * draining. Opening refuses a database written by another minor version of H2.</p>
 *
 * <p><b>PostgreSQL.</b> A connection pool serves the stores, one connection per transaction and
 * no lock: the stores' statements are written so that concurrent replicas never break their
 * contracts ({@link JdbcDialect}). Replicas opening the same database at once migrate it one at a
 * time, under an advisory lock.</p>
 *
 * <p>Opening applies the forward-only schema migrations shipped in this module, and refuses a
 * database written by a newer schema.</p>
 */
final class JdbcDatabase implements AutoCloseable {

    /** The schema version this module writes; one script {@code V<n>__*.sql} per version. */
    static final int SCHEMA_VERSION = 1;

    /** The default size of the PostgreSQL connection pool. */
    static final int DEFAULT_POOL_SIZE = 10;

    private static final String SCHEMA_KEY = "schema_version";
    private static final String H2_KEY = "h2_version";
    /** Key of the advisory lock that serialises the migrations of a PostgreSQL database. */
    private static final long MIGRATION_LOCK = 0x696b616e6f73L;

    private final JdbcDialect dialect;
    private final String description;
    private final Path path;
    private final Connection connection;
    private final HikariDataSource pool;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    private JdbcDatabase(JdbcDialect dialect, String description, Path path, Connection connection,
            HikariDataSource pool) {
        this.dialect = dialect;
        this.description = description;
        this.path = path;
        this.connection = connection;
        this.pool = pool;
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
        // DB_CLOSE_ON_EXIT=FALSE: H2's own shutdown hook would close the file while the engine
        // drains its tasks on SIGTERM (JVM hooks run concurrently); the owner closes it instead.
        String url = "jdbc:h2:file:" + absolute + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
        Connection connection;
        try {
            connection = new org.h2.Driver().connect(url, new Properties());
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open the H2 store at " + absolute + ": "
                    + e.getMessage(), e);
        }
        return migrated(new JdbcDatabase(JdbcDialect.H2, "H2 store at " + absolute, absolute,
                connection, null));
    }

    /**
     * Opens the PostgreSQL database at {@code url}, creating the stores' tables if needed.
     *
     * @param url a {@code jdbc:postgresql:} URL
     * @param user the user, or {@code null} when the URL or the server names none
     * @param password the password, or {@code null}
     * @param poolSize the most connections the pool opens
     * @throws IllegalArgumentException when {@code url} is not a PostgreSQL JDBC URL
     * @throws IllegalStateException when the database cannot be reached or holds a newer schema
     */
    static JdbcDatabase openPostgres(String url, String user, String password, int poolSize) {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("The postgres store needs a jdbc:postgresql: URL "
                    + "(durability.store.url)");
        }
        // Named without its query, which may carry credentials.
        String description = "PostgreSQL store at " + withoutQuery(url);
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url);
        if (user != null && !user.isBlank()) {
            config.setUsername(user);
        }
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setAutoCommit(false);
        config.setPoolName("ikanos-durable");
        config.setConnectionTimeout(10_000);
        // Fails here, not on the first task, when the database cannot be reached.
        config.setInitializationFailTimeout(10_000);
        HikariDataSource pool;
        try {
            pool = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot open the " + description + ": "
                    + rootMessage(e), e);
        }
        return migrated(new JdbcDatabase(JdbcDialect.POSTGRES, description, null, null, pool));
    }

    private static JdbcDatabase migrated(JdbcDatabase database) {
        try {
            database.migrate();
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : e.getMessage();
    }

    /** The database file of an H2 store, without H2's extension; {@code null} for PostgreSQL. */
    Path path() {
        return path;
    }

    /** The SQL dialect of the database. */
    JdbcDialect dialect() {
        return dialect;
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
        if (pool != null) {
            if (closed) {
                throw new IllegalStateException("The " + description + " is closed");
            }
            try (Connection pooled = pool.getConnection()) {
                return inTransaction(pooled, work);
            } catch (SQLException e) {
                throw failure(e);
            }
        }
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("The " + description + " is closed");
            }
            return inTransaction(connection, work);
        } finally {
            lock.unlock();
        }
    }

    private <T> T inTransaction(Connection c, Work<T> work) throws ConcurrentAppendException {
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            rollback(c);
            throw failure(e);
        } catch (ConcurrentAppendException | RuntimeException e) {
            rollback(c);
            throw e;
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
        if (pool != null) {
            closed = true;
            pool.close();
            return;
        }
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

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            // The original failure is the one to report.
        }
    }

    private IllegalStateException failure(SQLException e) {
        return new IllegalStateException(description + ": " + e.getMessage(), e);
    }

    // Migrations

    private void migrate() {
        call(c -> {
            if (dialect == JdbcDialect.POSTGRES) {
                // Held until this transaction ends: replicas starting together migrate in turn.
                try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    s.setLong(1, MIGRATION_LOCK);
                    s.execute();
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS store_meta (name VARCHAR(64) PRIMARY KEY, "
                        + "val VARCHAR(255) NOT NULL)");
            }
            int current = meta(c, SCHEMA_KEY).map(Integer::parseInt).orElse(0);
            if (current > SCHEMA_VERSION) {
                throw new IllegalStateException("The " + description + " has schema version "
                        + current + ", newer than this Ikanos supports (" + SCHEMA_VERSION
                        + "): use the Ikanos version that wrote it");
            }
            String running = null;
            if (dialect == JdbcDialect.H2) {
                running = h2Version(c);
                Optional<String> recorded = meta(c, H2_KEY);
                if (recorded.isPresent() && !minor(recorded.get()).equals(minor(running))) {
                    throw new IllegalStateException("The " + description + " was written by H2 "
                            + recorded.get() + ", and this Ikanos runs H2 " + running
                            + ": another minor version may not read the file. Use the Ikanos "
                            + "version that wrote it, or export and re-create the store");
                }
            }
            List<String> migrations = dialect.migrations();
            for (int version = current + 1; version <= SCHEMA_VERSION; version++) {
                runScript(c, migrations.get(version - 1));
            }
            putMeta(c, SCHEMA_KEY, Integer.toString(SCHEMA_VERSION));
            if (running != null) {
                putMeta(c, H2_KEY, running);
            }
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

    private void putMeta(Connection c, String name, String value) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                dialect.upsert("store_meta", List.of("name"), "name", "val"))) {
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
