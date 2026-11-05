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
import java.util.UUID;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.BlobRef;
import io.ikanos.durable.store.BlobStore;

/** Blob store in the {@code blobs} table, in the same file as the journal. */
final class JdbcBlobStore implements BlobStore {

    private final JdbcDatabase database;

    JdbcBlobStore(JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public BlobRef put(ExecutionId executionId, byte[] payload) {
        BlobRef ref = new BlobRef(executionId.value() + "/" + UUID.randomUUID());
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO blobs (ref, execution_id, payload) VALUES (?, ?, ?)")) {
                s.setString(1, ref.value());
                s.setString(2, executionId.value());
                s.setBytes(3, payload);
                s.executeUpdate();
            }
            return null;
        });
        return ref;
    }

    @Override
    public byte[] get(BlobRef ref) {
        return database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT payload FROM blobs WHERE ref = ?")) {
                s.setString(1, ref.value());
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("No blob " + ref.value());
                    }
                    return rs.getBytes(1);
                }
            }
        });
    }

    @Override
    public void deleteAll(ExecutionId executionId) {
        database.call(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "DELETE FROM blobs WHERE execution_id = ?")) {
                s.setString(1, executionId.value());
                s.executeUpdate();
            }
            return null;
        });
    }
}
