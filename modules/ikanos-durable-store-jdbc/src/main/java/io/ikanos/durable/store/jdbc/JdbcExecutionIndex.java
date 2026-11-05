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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.ExecutionIndex;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;

/** Execution index in the {@code executions} and {@code execution_labels} tables. */
final class JdbcExecutionIndex implements ExecutionIndex {

    private static final String COLUMNS =
            "execution_id, spec_digest, entry, status, created_at, deadline";

    private final JdbcDatabase database;

    JdbcExecutionIndex(JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public boolean create(ExecutionRecord record) {
        return database.call(c -> {
            if (exists(c, record.executionId())) {
                return false;
            }
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO executions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")) {
                s.setString(1, record.executionId().value());
                s.setString(2, record.specDigest());
                s.setString(3, record.entry());
                s.setString(4, record.status().name());
                JdbcDatabase.setInstant(s, 5, record.createdAt());
                JdbcDatabase.setInstant(s, 6, record.deadline());
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO execution_labels "
                    + "(execution_id, label_key, label_value) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, String> label : record.labels().entrySet()) {
                    s.setString(1, record.executionId().value());
                    s.setString(2, label.getKey());
                    s.setString(3, label.getValue());
                    s.addBatch();
                }
                s.executeBatch();
            }
            return true;
        });
    }

    private static boolean exists(Connection c, ExecutionId id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT 1 FROM executions WHERE execution_id = ?")) {
            s.setString(1, id.value());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionId executionId) {
        return database.call(c -> {
            List<ExecutionRecord> found = select(c,
                    "SELECT " + COLUMNS + " FROM executions WHERE execution_id = ?",
                    s -> s.setString(1, executionId.value()));
            return found.stream().findFirst();
        });
    }

    @Override
    public void updateStatus(ExecutionId executionId, ExecutionStatus status) {
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE executions SET status = ? WHERE execution_id = ?")) {
                s.setString(1, status.name());
                s.setString(2, executionId.value());
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ExecutionRecord> list(ExecutionStatus status, int limit) {
        return database.call(c -> select(c, "SELECT " + COLUMNS + " FROM executions "
                + "WHERE status = ? ORDER BY created_at, execution_id LIMIT ?", s -> {
                    s.setString(1, status.name());
                    s.setInt(2, limit);
                }));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static List<ExecutionRecord> select(Connection c, String sql, Binder binder)
            throws SQLException {
        List<ExecutionRecord> records = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            binder.bind(s);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    ExecutionId id = new ExecutionId(rs.getString("execution_id"));
                    records.add(new ExecutionRecord(id, rs.getString("spec_digest"),
                            rs.getString("entry"), ExecutionStatus.valueOf(rs.getString("status")),
                            JdbcDatabase.getInstant(rs, "created_at"),
                            JdbcDatabase.getInstant(rs, "deadline"), null));
                }
            }
        }
        List<ExecutionRecord> withLabels = new ArrayList<>(records.size());
        for (ExecutionRecord r : records) {
            withLabels.add(new ExecutionRecord(r.executionId(), r.specDigest(), r.entry(),
                    r.status(), r.createdAt(), r.deadline(), labels(c, r.executionId())));
        }
        return withLabels;
    }

    private static Map<String, String> labels(Connection c, ExecutionId id) throws SQLException {
        Map<String, String> labels = new HashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT label_key, label_value "
                + "FROM execution_labels WHERE execution_id = ?")) {
            s.setString(1, id.value());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    labels.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return labels;
    }
}
