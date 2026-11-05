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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.WorkQueue;

/**
 * Work queue in the {@code work_queue} and {@code leases} tables. Fencing tokens come from the
 * {@code fencing_tokens} sequence, so they keep growing across restarts. Time is the store's:
 * the clock given when the store opened.
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
            try (PreparedStatement s = c.prepareStatement("MERGE INTO work_queue "
                    + "(execution_id, due_at) KEY (execution_id) VALUES (?, ?)")) {
                s.setString(1, executionId.value());
                JdbcDatabase.setInstant(s, 2, dueAt);
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Lease> claim(String owner, Duration leaseFor) {
        return database.call(c -> {
            Instant now = clock.instant();
            String next;
            try (PreparedStatement s = c.prepareStatement("SELECT q.execution_id "
                    + "FROM work_queue q WHERE q.due_at <= ? AND NOT EXISTS (SELECT 1 FROM leases "
                    + "l WHERE l.execution_id = q.execution_id AND l.expires_at > ?) "
                    + "ORDER BY q.due_at, q.execution_id LIMIT 1")) {
                JdbcDatabase.setInstant(s, 1, now);
                JdbcDatabase.setInstant(s, 2, now);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Lease>empty();
                    }
                    next = rs.getString(1);
                }
            }
            long token;
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT NEXT VALUE FOR fencing_tokens")) {
                rs.next();
                token = rs.getLong(1);
            }
            try (PreparedStatement s = c.prepareStatement(
                    "DELETE FROM work_queue WHERE execution_id = ?")) {
                s.setString(1, next);
                s.executeUpdate();
            }
            Lease lease = new Lease(new ExecutionId(next), owner, token, now.plus(leaseFor));
            try (PreparedStatement s = c.prepareStatement("MERGE INTO leases (execution_id, "
                    + "owner, fencing_token, expires_at) KEY (execution_id) VALUES (?, ?, ?, ?)")) {
                s.setString(1, next);
                s.setString(2, owner);
                s.setLong(3, token);
                JdbcDatabase.setInstant(s, 4, lease.expiresAt());
                s.executeUpdate();
            }
            return Optional.of(lease);
        });
    }

    @Override
    public boolean renew(Lease lease, Duration leaseFor) {
        return database.call(c -> {
            Instant now = clock.instant();
            try (PreparedStatement s = c.prepareStatement("SELECT fencing_token, expires_at "
                    + "FROM leases WHERE execution_id = ? FOR UPDATE")) {
                s.setString(1, lease.executionId().value());
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != lease.fencingToken()
                            || JdbcDatabase.getInstant(rs, "expires_at").isBefore(now)) {
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
