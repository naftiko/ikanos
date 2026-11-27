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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The SQL that differs between the databases the stores run on.
 *
 * <p>H2 serves one process through one connection, so a read followed by a write never races.
 * PostgreSQL serves several replicas at once: every write that must not be duplicated is a single
 * atomic statement ({@code ON CONFLICT}), since a failed statement aborts its transaction there and
 * cannot be caught and turned into a {@code false}.</p>
 */
enum JdbcDialect {

    H2("V1__stores.sql"),

    POSTGRES("postgres/V1__stores.sql");

    private final List<String> migrations;

    JdbcDialect(String... migrations) {
        this.migrations = List.of(migrations);
    }

    /** The migration scripts, one per schema version, from version 1 on. */
    List<String> migrations() {
        return migrations;
    }

    /** The expression drawing the next value of {@code sequence}. */
    String nextValue(String sequence) {
        return this == H2 ? "NEXT VALUE FOR " + sequence : "nextval('" + sequence + "')";
    }

    /**
     * An insert of {@code columns} into {@code table} that replaces the row with the same
     * {@code key}, one parameter per column, in order.
     */
    String upsert(String table, List<String> key, String... columns) {
        String names = String.join(", ", columns);
        String values = Arrays.stream(columns).map(c -> "?").collect(Collectors.joining(", "));
        if (this == H2) {
            return "MERGE INTO " + table + " (" + names + ") KEY (" + String.join(", ", key)
                    + ") VALUES (" + values + ")";
        }
        String updates = Arrays.stream(columns).filter(c -> !key.contains(c))
                .map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + names + ") VALUES (" + values + ") ON CONFLICT ("
                + String.join(", ", key) + ") DO UPDATE SET " + updates;
    }

    /**
     * An insert that does nothing when a row with the same {@code key} exists: its update count
     * is 0. Only PostgreSQL uses it; H2 checks first, under its single connection.
     */
    String insertIfAbsent(String table, List<String> key, String... columns) {
        String values = Arrays.stream(columns).map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + values
                + ") ON CONFLICT (" + String.join(", ", key) + ") DO NOTHING";
    }

    /** Whether concurrent writers share the database, so writes must be atomic statements. */
    boolean isShared() {
        return this == POSTGRES;
    }
}
