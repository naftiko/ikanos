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
package io.ikanos.spec.consumes.sql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Consumed relational database specification ({@code consumes: type: sql}).
 *
 * <p>Targets one database over JDBC. Declares the procedures a capability may call, keyed by
 * procedure name, like the tools of {@code type: mcp}. Nothing else in the database is
 * reachable.</p>
 *
 * <h2>Thread safety</h2>
 * Scalar fields are held in {@link AtomicReference}s and the procedures map is synchronized,
 * matching {@code McpClientSpec} (SonarQube {@code java:S3077}).
 */
@JsonDeserialize(using = ValueDeserializer.None.class)
public class SqlClientSpec extends ClientSpec {

    /** Value of {@code type} for this spec. */
    public static final String TYPE = "sql";

    /** {@code discovery: verify} — check the connection and every procedure at start. */
    public static final String DISCOVERY_VERIFY = "verify";

    /** {@code discovery: off} — start-up does not wait for a connection. */
    public static final String DISCOVERY_OFF = OFF;

    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<SqlConnectionSpec> connection = new AtomicReference<>();
    private final AtomicReference<SqlPoolSpec> pool = new AtomicReference<>();
    private final AtomicReference<String> discovery = new AtomicReference<>(DISCOVERY_VERIFY);

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = SqlProcedureMapDeserializer.class)
    private final Map<String, SqlProcedureSpec> procedures =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public SqlClientSpec() {
        super(TYPE, null);
    }

    public SqlClientSpec(String namespace, SqlConnectionSpec connection) {
        super(TYPE, namespace);
        this.connection.set(connection);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    /** @return the JDBC URL and credentials */
    public SqlConnectionSpec getConnection() {
        return connection.get();
    }

    public void setConnection(SqlConnectionSpec connection) {
        this.connection.set(connection);
    }

    /** @return the connection pool bounds, or {@code null} for the defaults */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public SqlPoolSpec getPool() {
        return pool.get();
    }

    public void setPool(SqlPoolSpec pool) {
        this.pool.set(pool);
    }

    /** @return {@code verify} (default) or {@code off} */
    public String getDiscovery() {
        return discovery.get();
    }

    public void setDiscovery(String discovery) {
        this.discovery.set(normalizeOff(discovery, DISCOVERY_VERIFY));
    }

    /** @return {@code true} when the adapter must check the database at start */
    @JsonIgnore
    public boolean isDiscoveryVerify() {
        return !DISCOVERY_OFF.equalsIgnoreCase(getDiscovery());
    }

    /** @return declared procedures keyed by procedure name */
    public Map<String, SqlProcedureSpec> getProcedures() {
        return procedures;
    }

    public void setProcedures(Map<String, SqlProcedureSpec> values) {
        procedures.clear();
        if (values != null) {
            values.forEach((name, procedure) -> {
                procedure.setName(name);
                procedures.put(name, procedure);
            });
        }
    }
}
