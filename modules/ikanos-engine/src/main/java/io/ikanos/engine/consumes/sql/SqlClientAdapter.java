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
package io.ikanos.engine.consumes.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.consumes.sql.SqlConnectionSpec;
import io.ikanos.spec.consumes.sql.SqlPoolSpec;
import io.ikanos.spec.consumes.sql.SqlProcedureSpec;

/**
 * Consumed adapter for one relational database over JDBC ({@code consumes: type: sql}).
 *
 * <p>Reached from steps through {@link #prepare(String, Map)} like any consumed adapter. Only
 * declared procedures are callable; caller values reach the database only as bind variables.</p>
 *
 * <ul>
 *   <li><b>Build</b> — {@code connection.url}, {@code username} and {@code password} are
 *       resolved from the capability bindings once, never from call parameters, so a caller
 *       cannot choose the database. Driver properties that load classes or read local files are
 *       refused. Each statement is parsed once, in the MySQL dialect for MySQL and MariaDB URLs:
 *       its {@code :name} placeholders must match declared inputs, every {@code sqlType} must be a
 *       JDBC type, and it must read.</li>
 *   <li><b>Start</b> — a HikariCP pool is created. It opens its connections in the background
 *       and {@code start()} does not wait for them, so a database that is down at start-up only
 *       fails the calls that need it.</li>
 *   <li><b>Call</b> — a pooled connection is set read-only, the statement is prepared and bound,
 *       and its rows become the document of
 *       {@link SqlRowDocument}. Failures become a status mapped from the SQLState
 *       ({@link SqlConsumedResult#failure}); the driver detail goes to the log only.</li>
 * </ul>
 */
public class SqlClientAdapter extends ClientAdapter {

    private static final Logger LOGGER = Logger.getLogger(SqlClientAdapter.class.getName());

    /**
     * One declared procedure, checked and parsed when the adapter is built.
     *
     * @param name the procedure name, the call target after the namespace
     * @param spec the declaration
     * @param statement the parsed statement
     * @param view the output declarations
     * @param operation the span operation, such as {@code SELECT}
     */
    record Procedure(String name, SqlProcedureSpec spec, SqlStatement statement,
            ConsumedOperationView view, String operation) {
    }

    private final JdbcUrl jdbcUrl;
    private final String url;
    private final String username;
    private final String password;
    private final Map<String, Procedure> procedures;
    private volatile HikariDataSource pool;

    public SqlClientAdapter(Capability capability, SqlClientSpec spec) {
        super(capability, spec);
        String namespace = spec.getNamespace();
        SqlConnectionSpec connection = spec.getConnection();
        if (connection == null || connection.getUrl() == null
                || connection.getUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "consumes type sql '" + namespace + "' requires connection.url");
        }
        Map<String, Object> bindings = capability != null ? capability.getBindings() : null;
        this.url = resolveFromBindings(namespace, "connection.url", connection.getUrl(),
                bindings);
        this.username = resolveFromBindings(namespace, "connection.username",
                connection.getUsername(), bindings);
        this.password = resolveFromBindings(namespace, "connection.password",
                connection.getPassword(), bindings);
        this.jdbcUrl = JdbcUrl.check(namespace, url, connection.getProperties());
        this.procedures = Collections.unmodifiableMap(buildProcedures(spec,
                jdbcUrl.mysqlDialect()));
    }

    static Map<String, Procedure> buildProcedures(SqlClientSpec spec, boolean mysql) {
        Map<String, Procedure> built = new LinkedHashMap<>();
        for (SqlProcedureSpec procedure : spec.getProcedures().values()) {
            String where = "consumes type sql '" + spec.getNamespace() + "' procedure '"
                    + procedure.getName() + "'";
            if (procedure.getStatement() == null || procedure.getStatement().isBlank()) {
                throw new IllegalArgumentException(where + " requires a statement");
            }
            SqlStatement statement;
            try {
                statement = SqlStatement.parse(procedure.getStatement(), mysql);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
            String nonRead = statement.nonReadKeyword();
            if (nonRead != null) {
                throw new IllegalArgumentException(where + " must read (SELECT, WITH or VALUES);"
                        + " it uses " + nonRead);
            }
            procedure.getInputParameters()
                    .forEach(input -> SqlBind.targetType(input, where));
            for (String placeholder : statement.placeholders()) {
                if (inputFor(procedure, placeholder) == null) {
                    throw new IllegalArgumentException(where + " uses placeholder ':"
                            + placeholder + "', which matches no declared input");
                }
            }
            built.put(procedure.getName(), new Procedure(procedure.getName(), procedure,
                    statement, ConsumedOperationView.of(procedure, null, null), "SELECT"));
        }
        return built;
    }

    /** @return the input whose placeholder (or, without one, whose key) is {@code name} */
    static InputParameterSpec inputFor(SqlProcedureSpec procedure, String name) {
        for (InputParameterSpec input : procedure.getInputParameters()) {
            if (name.equals(placeholderOf(input))) {
                return input;
            }
        }
        return null;
    }

    static String placeholderOf(InputParameterSpec input) {
        String placeholder = input.getPlaceholder();
        return placeholder != null && !placeholder.isBlank() ? placeholder : input.getName();
    }

    public SqlClientSpec getSqlClientSpec() {
        return (SqlClientSpec) getSpec();
    }

    JdbcUrl jdbcUrl() {
        return jdbcUrl;
    }

    /** @return the pool, or {@code null} before {@link #start()} and after {@link #stop()} */
    DataSource dataSource() {
        return pool;
    }

    @Override
    public boolean declaresOperation(String operationName) {
        return procedures.containsKey(operationName);
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        Procedure procedure = procedures.get(operationName);
        if (procedure == null) {
            return null;
        }
        String where = getNamespace() + "." + operationName;
        List<SqlBind> binds = new ArrayList<>();
        for (String placeholder : procedure.statement().placeholders()) {
            InputParameterSpec input = inputFor(procedure.spec(), placeholder);
            Object value = parameters != null ? parameters.get(input.getName()) : null;
            if (value == null && input.isRequiredOrDefault(true)) {
                throw new IllegalArgumentException("Missing required input '" + input.getName()
                        + "' for SQL procedure " + where);
            }
            binds.add(SqlBind.of(input, value, where));
        }
        return new SqlInvocation(this, procedure, List.copyOf(binds));
    }

    /** Run one call. Called inside the client span by {@link SqlInvocation}. */
    ConsumedResult execute(Procedure procedure, List<SqlBind> binds) {
        HikariDataSource current = pool;
        if (current == null) {
            throw new IllegalStateException("consumes type sql '" + getNamespace()
                    + "' is not started");
        }
        try (Connection connection = current.getConnection()) {
            readOnly(connection);
            try (PreparedStatement statement =
                    connection.prepareStatement(procedure.statement().jdbcSql())) {
                statement.setQueryTimeout(timeoutSeconds(procedure.spec()));
                if (procedure.spec().getFetchSize() != null) {
                    statement.setFetchSize(procedure.spec().getFetchSize());
                }
                for (int i = 0; i < binds.size(); i++) {
                    binds.get(i).bind(statement, i + 1);
                }
                if (!statement.execute()) {
                    return SqlConsumedResult.success(procedure.view(), SqlRowDocument.empty());
                }
                try (ResultSet rows = statement.getResultSet()) {
                    return SqlConsumedResult.success(procedure.view(), SqlRowDocument.read(rows));
                }
            }
        } catch (SQLException e) {
            log(procedure, e);
            return SqlConsumedResult.failure(procedure.view(), e);
        } catch (SqlClientException e) {
            LOGGER.warning("SQL procedure " + getNamespace() + "." + procedure.name()
                    + " failed: " + e.getMessage());
            return SqlConsumedResult.failure(procedure.view(), e);
        }
    }

    /**
     * Ask for a read-only connection. How much that guards depends on the driver: PostgreSQL
     * (with the {@code readOnlyMode=always} that {@link #poolConfig()} sets) and MySQL make the
     * session read-only on the server; SQL Server, Oracle and SQLite treat it as a hint or refuse
     * it. The statement check at build time and a read-only database user are the guard there.
     */
    private void readOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException unsupported) {
            LOGGER.log(Level.FINE, "Driver for consumes type sql ''{0}'' cannot set a connection"
                    + " read-only; relying on the statement check", getNamespace());
        }
    }

    static int timeoutSeconds(SqlProcedureSpec spec) {
        int millis = spec.getTimeoutMs() != null ? spec.getTimeoutMs()
                : SqlProcedureSpec.DEFAULT_TIMEOUT_MS;
        return Math.max(1, (millis + 999) / 1000);
    }

    private void log(Procedure procedure, SQLException e) {
        LOGGER.log(Level.WARNING, "SQL procedure " + getNamespace() + "." + procedure.name()
                + " failed with SQLState " + e.getSQLState() + ", vendor code "
                + e.getErrorCode(), e);
    }

    @Override
    public void start() throws Exception {
        if (pool != null) {
            return;
        }
        pool = new HikariDataSource(poolConfig());
    }

    HikariConfig poolConfig() {
        SqlClientSpec spec = getSqlClientSpec();
        HikariConfig config = new HikariConfig();
        config.setPoolName("ikanos-sql-" + spec.getNamespace());
        config.setJdbcUrl(url);
        if (username != null) {
            config.setUsername(username);
        }
        if (password != null) {
            config.setPassword(password);
        }
        SqlConnectionSpec connection = spec.getConnection();
        if (connection.getDriverClassName() != null) {
            config.setDriverClassName(connection.getDriverClassName());
        }
        connection.getProperties().forEach(config::addDataSourceProperty);
        if ("postgresql".equals(jdbcUrl.system()) && !setsProperty(connection, "readOnlyMode")) {
            // pgjdbc ignores setReadOnly(true) in autocommit unless readOnlyMode is always
            config.addDataSourceProperty("readOnlyMode", "always");
        }
        config.setAutoCommit(true);
        // Do not wait for a connection at start: a database that is down only fails the calls
        // that need it. The pool still opens its connections in the background.
        config.setInitializationFailTimeout(-1);
        SqlPoolSpec bounds = spec.getPool();
        if (bounds != null) {
            if (bounds.getMaxSize() != null) {
                config.setMaximumPoolSize(bounds.getMaxSize());
            }
            if (bounds.getMinIdle() != null) {
                config.setMinimumIdle(bounds.getMinIdle());
            }
            if (bounds.getConnectionTimeoutMs() != null) {
                config.setConnectionTimeout(bounds.getConnectionTimeoutMs());
            }
            if (bounds.getMaxLifetimeMs() != null) {
                config.setMaxLifetime(bounds.getMaxLifetimeMs());
            }
        }
        return config;
    }

    /** @return whether the URL or {@code connection.properties} sets {@code property} */
    boolean setsProperty(SqlConnectionSpec connection, String property) {
        Pattern inUrl = Pattern.compile("[?&;(,]\\s*" + Pattern.quote(property) + "\\s*=",
                Pattern.CASE_INSENSITIVE);
        return connection.getProperties().keySet().stream()
                .anyMatch(key -> key.trim().equalsIgnoreCase(property))
                || inUrl.matcher(JdbcUrl.percentDecoded(url)).find();
    }

    @Override
    public void stop() throws Exception {
        HikariDataSource current = pool;
        pool = null;
        if (current != null) {
            current.close();
        }
    }
}
