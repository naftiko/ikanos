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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.JournalStore;
import io.ikanos.durable.store.StoreFeature;

/**
 * Journal in the {@code journal} table, with each execution's last sequence and fencing token in
 * {@code journal_heads}. An append checks the head and inserts its entries in one transaction.
 */
final class JdbcJournalStore implements JournalStore {

    private final JdbcDatabase database;

    JdbcJournalStore(JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public Set<StoreFeature> features() {
        return EnumSet.of(StoreFeature.TRANSACTIONAL, StoreFeature.CONDITIONAL_WRITES,
                StoreFeature.DURABLE);
    }

    @Override
    public void append(ExecutionId executionId, long expectedSequence, long fencingToken,
            List<JournalEntry> entries) throws ConcurrentAppendException {
        database.transact(c -> {
            long lastSequence = 0;
            long currentToken = Long.MIN_VALUE;
            try (PreparedStatement s = c.prepareStatement("SELECT last_sequence, fencing_token "
                    + "FROM journal_heads WHERE execution_id = ? FOR UPDATE")) {
                s.setString(1, executionId.value());
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next()) {
                        lastSequence = rs.getLong(1);
                        currentToken = rs.getLong(2);
                    }
                }
            }
            if (fencingToken < currentToken) {
                throw new ConcurrentAppendException("Stale fencing token " + fencingToken
                        + " for " + executionId + "; current is " + currentToken);
            }
            if (lastSequence != expectedSequence) {
                throw new ConcurrentAppendException("Journal of " + executionId + " is at "
                        + lastSequence + ", not " + expectedSequence);
            }
            long next = expectedSequence + 1;
            for (JournalEntry entry : entries) {
                if (entry.sequence() != next++) {
                    throw new IllegalArgumentException(
                            "Entries must continue the journal without gaps: " + entry);
                }
            }
            insert(c, executionId, entries);
            try (PreparedStatement s = c.prepareStatement("MERGE INTO journal_heads "
                    + "(execution_id, last_sequence, fencing_token) KEY (execution_id) "
                    + "VALUES (?, ?, ?)")) {
                s.setString(1, executionId.value());
                s.setLong(2, expectedSequence + entries.size());
                s.setLong(3, fencingToken);
                s.executeUpdate();
            }
            return null;
        });
    }

    private static void insert(Connection c, ExecutionId executionId, List<JournalEntry> entries)
            throws SQLException, ConcurrentAppendException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO journal "
                + "(execution_id, sequence, kind, path, payload) VALUES (?, ?, ?, ?, ?)")) {
            for (JournalEntry entry : entries) {
                s.setString(1, executionId.value());
                s.setLong(2, entry.sequence());
                s.setString(3, entry.kind().name());
                s.setString(4, entry.path() == null ? null : entry.path().value());
                s.setBytes(5, entry.payload());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            // Another writer took the same sequence: the head check is the rule, this the backstop.
            throw new ConcurrentAppendException("Journal of " + executionId
                    + " already holds one of these sequences");
        }
    }

    /** Whether {@code e}, or a failure it chains, is a primary key violation (SQL state 23505). */
    static boolean isDuplicateKey(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            if (DUPLICATE_KEY.equals(current.getSQLState())) {
                return true;
            }
        }
        return e.getCause() instanceof SQLException cause && DUPLICATE_KEY.equals(cause.getSQLState());
    }

    private static final String DUPLICATE_KEY = "23505";

    @Override
    public List<JournalEntry> read(ExecutionId executionId) {
        return database.call(c -> {
            List<JournalEntry> entries = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT sequence, kind, path, payload "
                    + "FROM journal WHERE execution_id = ? ORDER BY sequence")) {
                s.setString(1, executionId.value());
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString(3);
                        entries.add(new JournalEntry(rs.getLong(1),
                                JournalEntry.Kind.valueOf(rs.getString(2)),
                                path == null ? null : new NodePath(path), rs.getBytes(4)));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    @Override
    public void truncate(ExecutionId executionId) {
        database.call(c -> {
            for (String table : List.of("journal", "journal_heads")) {
                try (PreparedStatement s = c.prepareStatement(
                        "DELETE FROM " + table + " WHERE execution_id = ?")) {
                    s.setString(1, executionId.value());
                    s.executeUpdate();
                }
            }
            return null;
        });
    }
}
