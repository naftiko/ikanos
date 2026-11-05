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
import java.util.LinkedHashMap;
import java.util.Map;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.SignalInbox;

/**
 * Signal inbox in the {@code inbox} table. A delivered update keeps its row once acknowledged,
 * so a second delivery for the same path is still refused.
 */
final class JdbcSignalInbox implements SignalInbox {

    private final JdbcDatabase database;

    JdbcSignalInbox(JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public boolean deliver(ExecutionId executionId, NodePath path, byte[] payload) {
        return database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT 1 FROM inbox WHERE execution_id = ? AND path = ?")) {
                s.setString(1, executionId.value());
                s.setString(2, path.value());
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO inbox (execution_id, "
                    + "path, delivered, payload, acknowledged) "
                    + "VALUES (?, ?, NEXT VALUE FOR inbox_order, ?, FALSE)")) {
                s.setString(1, executionId.value());
                s.setString(2, path.value());
                s.setBytes(3, payload);
                s.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public Map<NodePath, byte[]> pending(ExecutionId executionId) {
        return database.call(c -> {
            Map<NodePath, byte[]> pending = new LinkedHashMap<>();
            try (PreparedStatement s = c.prepareStatement("SELECT path, payload FROM inbox "
                    + "WHERE execution_id = ? AND acknowledged = FALSE ORDER BY delivered")) {
                s.setString(1, executionId.value());
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        pending.put(new NodePath(rs.getString(1)), rs.getBytes(2));
                    }
                }
            }
            return pending;
        });
    }

    @Override
    public void acknowledge(ExecutionId executionId, NodePath path) {
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE inbox SET acknowledged = TRUE "
                    + "WHERE execution_id = ? AND path = ?")) {
                s.setString(1, executionId.value());
                s.setString(2, path.value());
                s.executeUpdate();
            }
            return null;
        });
    }
}
