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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.WorkQueue;

/**
 * Work queue in the {@code work_queue} and {@code leases} tables. Fencing tokens come from the
 * {@code fencing_tokens} sequence, so they keep growing across restarts. Time is the store's:
 * the clock given when the store opened, so replicas sharing a PostgreSQL store must keep their
 * clocks in step, within a small fraction of a lease.
 *
 * <p>On PostgreSQL, a lease is written by one atomic statement that takes it only when no lease
 * is held or the one held has expired, and a claim skips the queued executions another replica is
 * claiming ({@code SKIP LOCKED}): replicas racing for an execution get at most one lease.</p>
 */
final class JdbcWorkQueue implements WorkQueue {

    private final JdbcDatabase database;
    private final Clock clock;

    JdbcWorkQueue(JdbcDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @Override
    public void enqueue(ExecutionId executionId, Instant dueAt) {
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(database.dialect().upsert(
                    "work_queue", List.of("execution_id"), "execution_id", "due_at"))) {
                s.setString(1, executionId.value());
                JdbcDatabase.setInstant(s, 2, dueAt);
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Lease> claim(String owner, Duration leaseFor) {
        JdbcDialect dialect = database.dialect();
        return database.call(c -> {
            Instant now = clock.instant();
            String next;
            try (PreparedStatement s = c.prepareStatement("SELECT q.execution_id "
                    + "FROM work_queue q WHERE q.due_at <= ? AND NOT EXISTS (SELECT 1 FROM leases "
                    + "l WHERE l.execution_id = q.execution_id AND l.expires_at > ?) "
                    + "ORDER BY q.due_at, q.execution_id LIMIT 1"
                    + (dialect.isShared() ? " FOR UPDATE OF q SKIP LOCKED" : ""))) {
                JdbcDatabase.setInstant(s, 1, now);
                JdbcDatabase.setInstant(s, 2, now);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Lease>empty();
                    }
                    next = rs.getString(1);
                }
            }
            Optional<Lease> lease = take(c, new ExecutionId(next), owner, now, leaseFor);
            if (lease.isPresent()) {
                try (PreparedStatement s = c.prepareStatement(
                        "DELETE FROM work_queue WHERE execution_id = ?")) {
                    s.setString(1, next);
                    s.executeUpdate();
                }
            }
            return lease;
        });
    }

    @Override
    public Optional<Lease> acquire(ExecutionId executionId, String owner, Duration leaseFor) {
        return database.call(c -> take(c, executionId, owner, clock.instant(), leaseFor));
    }

    /**
     * Writes a new lease on {@code executionId}, unless one is held strictly after {@code now}.
     */
    private Optional<Lease> take(Connection c, ExecutionId executionId, String owner,
            Instant now, Duration leaseFor) throws SQLException {
        JdbcDialect dialect = database.dialect();
        if (!dialect.isShared()) {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT 1 FROM leases WHERE execution_id = ? AND expires_at > ?")) {
                s.setString(1, executionId.value());
                JdbcDatabase.setInstant(s, 2, now);
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next()) {
                        return Optional.empty();
                    }
                }
            }
        }
        long token;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT " + dialect.nextValue("fencing_tokens"))) {
            rs.next();
            token = rs.getLong(1);
        }
        Lease lease = new Lease(executionId, owner, token, now.plus(leaseFor));
        String write = dialect.isShared()
                ? "INSERT INTO leases (execution_id, owner, fencing_token, expires_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (execution_id) DO UPDATE SET "
                        + "owner = EXCLUDED.owner, fencing_token = EXCLUDED.fencing_token, "
                        + "expires_at = EXCLUDED.expires_at WHERE leases.expires_at <= ?"
                : dialect.upsert("leases", List.of("execution_id"), "execution_id", "owner",
                        "fencing_token", "expires_at");
        try (PreparedStatement s = c.prepareStatement(write)) {
            s.setString(1, executionId.value());
            s.setString(2, owner);
            s.setLong(3, token);
            JdbcDatabase.setInstant(s, 4, lease.expiresAt());
            if (dialect.isShared()) {
                JdbcDatabase.setInstant(s, 5, now);
            }
            if (s.executeUpdate() == 0) {
                return Optional.empty();
            }
        }
        return Optional.of(lease);
    }

    @Override
    public boolean renew(Lease lease, Duration leaseFor) {
        return database.call(c -> {
            Instant now = clock.instant();
            // Expired from expires_at on, as in claim: a claimable lease is never renewed.
            try (PreparedStatement s = c.prepareStatement("SELECT fencing_token, expires_at "
                    + "FROM leases WHERE execution_id = ? FOR UPDATE")) {
                s.setString(1, lease.executionId().value());
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != lease.fencingToken()
                            || !JdbcDatabase.getInstant(rs, "expires_at").isAfter(now)) {
                        return false;
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE leases SET expires_at = ? WHERE execution_id = ?")) {
                JdbcDatabase.setInstant(s, 1, now.plus(leaseFor));
                s.setString(2, lease.executionId().value());
                s.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public void release(Lease lease) {
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "DELETE FROM leases WHERE execution_id = ? AND fencing_token = ?")) {
                s.setString(1, lease.executionId().value());
                s.setLong(2, lease.fencingToken());
                s.executeUpdate();
            }
            return null;
        });
    }
}
