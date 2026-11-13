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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.consumes.sql.SqlConnectionSpec;
import io.ikanos.spec.consumes.sql.SqlOutParameterSpec;
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
     * @param statement the parsed statement, or {@code null} for a stored procedure
     * @param jdbcSql the SQL sent to the driver: the statement with {@code ?}, or
     *     {@code {call <name>(?, ...)}}
     * @param inputs the inputs in bind order, one entry per {@code ?} they fill
     * @param positions the position of each entry of {@code inputs}
     * @param outs the {@code OUT} and {@code INOUT} parameters, by position
     * @param view the output declarations
     * @param operation the span operation: {@code SELECT}, {@code CALL}, or the write keyword
     * @param readOnly whether the procedure only reads; {@code false} runs it in a transaction
     * @param generatedKeys whether to ask the driver for the keys an {@code INSERT} generates
     */
    record Procedure(String name, SqlProcedureSpec spec, SqlStatement statement, String jdbcSql,
            List<InputParameterSpec> inputs, List<Integer> positions,
            List<SqlOutParameterSpec> outs, ConsumedOperationView view, String operation,
            boolean readOnly, boolean generatedKeys) {

        boolean isStored() {
            return statement == null;
        }

        /**
         * @return the {@code sqlType} of the {@code OUT} half of an {@code INOUT} parameter at
         *     {@code position}, which its input half is bound as, or {@code null}
         */
        JDBCType inoutType(int position) {
            for (SqlOutParameterSpec out : outs) {
                if (out.getPosition() == position) {
                    return JDBCType.valueOf(out.getSqlType().trim().toUpperCase(Locale.ROOT));
                }
            }
            return null;
        }
    }

    /** A stored procedure name, optionally qualified by schema and catalog. */
    private static final Pattern STORED_PROCEDURE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}$");

    private final JdbcUrl jdbcUrl;
    private final String url;
    private final String username;
    private final String password;
    private final Map<String, String> properties;
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
        Map<String, String> resolved = new LinkedHashMap<>();
        connection.getProperties().forEach((key, value) -> resolved.put(key, resolveFromBindings(
                namespace, "connection.properties." + key, value, bindings)));
        this.properties = Collections.unmodifiableMap(resolved);
        this.jdbcUrl = JdbcUrl.check(namespace, url, properties);
        this.procedures = Collections.unmodifiableMap(buildProcedures(spec,
                jdbcUrl.dialect()));
    }

    static Map<String, Procedure> buildProcedures(SqlClientSpec spec,
            SqlStatement.Dialect dialect) {
        Map<String, Procedure> built = new LinkedHashMap<>();
        for (SqlProcedureSpec procedure : spec.getProcedures().values()) {
            String where = "consumes type sql '" + spec.getNamespace() + "' procedure '"
                    + procedure.getName() + "'";
            boolean hasStatement = procedure.getStatement() != null
                    && !procedure.getStatement().isBlank();
            boolean hasStored = procedure.getStoredProcedure() != null
                    && !procedure.getStoredProcedure().isBlank();
            if (hasStatement == hasStored) {
                throw new IllegalArgumentException(where
                        + " requires exactly one of statement and storedProcedure");
            }
            if (procedure.getMaxAffectedRows() != null
                    && !Boolean.FALSE.equals(procedure.getReadOnly())) {
                throw new IllegalArgumentException(where
                        + " declares maxAffectedRows, which only applies with readOnly: false");
            }
            if (hasStored && procedure.getMaxAffectedRows() != null) {
                throw new IllegalArgumentException(where + " declares maxAffectedRows on a"
                        + " storedProcedure; drivers do not report the rows a procedure changes,"
                        + " so the cap could not hold");
            }
            procedure.getInputParameters()
                    .forEach(input -> SqlBind.targetType(input, where));
            Procedure parsed = hasStatement ? statementProcedure(procedure, where, dialect)
                    : storedProcedure(procedure, where);
            built.put(procedure.getName(), parsed);
        }
        return built;
    }

    static Procedure statementProcedure(SqlProcedureSpec procedure, String where,
            SqlStatement.Dialect dialect) {
        if (!procedure.getOutParameters().isEmpty()) {
            throw new IllegalArgumentException(where
                    + " declares outParameters, which only a storedProcedure can return");
        }
        SqlStatement statement;
        try {
            statement = SqlStatement.parse(procedure.getStatement(), dialect);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
        boolean readOnly = !Boolean.FALSE.equals(procedure.getReadOnly());
        String operation = "SELECT";
        if (readOnly) {
            String nonRead = statement.nonReadKeyword();
            if (nonRead != null) {
                throw new IllegalArgumentException(where + " must read (SELECT, WITH or VALUES)"
                        + " unless it declares readOnly: false; it uses " + nonRead);
            }
        } else {
            try {
                operation = statement.writeOperation();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
        }
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (InputParameterSpec input : procedure.getInputParameters()) {
            if (!declared.add(placeholderOf(input))) {
                throw new IllegalArgumentException(where + " declares placeholder ':"
                        + placeholderOf(input) + "' on two inputs");
            }
        }
        // A RETURNING (PostgreSQL, Oracle) or OUTPUT (SQL Server) clause returns rows itself
        boolean generatedKeys = ("INSERT".equals(operation) || "MERGE".equals(operation))
                && !statement.returnsRows();
        List<InputParameterSpec> inputs = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (String placeholder : statement.placeholders()) {
            InputParameterSpec input = inputFor(procedure, placeholder);
            if (input == null) {
                throw new IllegalArgumentException(where + " uses placeholder ':"
                        + placeholder + "', which matches no declared input");
            }
            inputs.add(input);
            positions.add(inputs.size());
        }
        // SQL Server requires MERGE to end with ';': keep the one the author wrote, on a line of
        // its own so that a trailing line comment cannot swallow it
        String jdbcSql = "MERGE".equals(operation) && statement.terminated()
                ? statement.jdbcSql() + "\n;" : statement.jdbcSql();
        return new Procedure(procedure.getName(), procedure, statement, jdbcSql,
                List.copyOf(inputs), List.copyOf(positions), List.of(),
                ConsumedOperationView.of(procedure, null, null), operation, readOnly,
                generatedKeys);
    }

    /**
     * Check a stored procedure: a plain or qualified name, a {@code readOnly} attestation, and
     * positions that are unique and contiguous from 1, where an {@code INOUT} parameter takes the
     * same position in {@code inputParameters} and {@code outParameters}.
     */
    private static Procedure storedProcedure(SqlProcedureSpec procedure, String where) {
        String name = procedure.getStoredProcedure().trim();
        if (!STORED_PROCEDURE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where
                    + " storedProcedure must be a name such as schema.procedure");
        }
        if (procedure.getReadOnly() == null) {
            throw new IllegalArgumentException(where + " must declare readOnly: the engine"
                    + " cannot tell whether a stored procedure changes data");
        }
        TreeMap<Integer, InputParameterSpec> inputsByPosition = new TreeMap<>();
        for (InputParameterSpec input : procedure.getInputParameters()) {
            Integer position = input.getPosition();
            if (position == null || position < 1) {
                throw new IllegalArgumentException(where + " input '" + input.getName()
                        + "' requires a position of 1 or more");
            }
            if (inputsByPosition.put(position, input) != null) {
                throw new IllegalArgumentException(where + " declares two inputs at position "
                        + position);
            }
        }
        TreeMap<Integer, SqlOutParameterSpec> outsByPosition = new TreeMap<>();
        for (SqlOutParameterSpec out : procedure.getOutParameters().values()) {
            Integer position = out.getPosition();
            if (position == null || position < 1) {
                throw new IllegalArgumentException(where + " out parameter '" + out.getName()
                        + "' requires a position of 1 or more");
            }
            SqlBind.jdbcType(out.getSqlType(), "out parameter '" + out.getName() + "'", where);
            if (outsByPosition.put(position, out) != null) {
                throw new IllegalArgumentException(where
                        + " declares two out parameters at position " + position);
            }
            InputParameterSpec inout = inputsByPosition.get(position);
            if (inout != null && inout.getSqlType() != null && !inout.getSqlType().isBlank()
                    && !inout.getSqlType().trim().equalsIgnoreCase(out.getSqlType().trim())) {
                throw new IllegalArgumentException(where + " declares INOUT position "
                        + position + " as " + inout.getSqlType() + " in and " + out.getSqlType()
                        + " out; an INOUT parameter has one type");
            }
        }
        int highest = Math.max(
                inputsByPosition.isEmpty() ? 0 : inputsByPosition.lastKey(),
                outsByPosition.isEmpty() ? 0 : outsByPosition.lastKey());
        for (int position = 1; position <= highest; position++) {
            if (!inputsByPosition.containsKey(position) && !outsByPosition.containsKey(position)) {
                throw new IllegalArgumentException(where + " has no parameter at position "
                        + position + "; positions must be contiguous from 1");
            }
        }
        StringBuilder call = new StringBuilder("{call ").append(name).append('(');
        for (int position = 1; position <= highest; position++) {
            call.append(position == 1 ? "?" : ", ?");
        }
        call.append(")}");
        return new Procedure(procedure.getName(), procedure, null, call.toString(),
                List.copyOf(inputsByPosition.values()), List.copyOf(inputsByPosition.keySet()),
                List.copyOf(outsByPosition.values()),
                ConsumedOperationView.of(procedure, null, null), "CALL",
                procedure.getReadOnly(), false);
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
        for (int i = 0; i < procedure.inputs().size(); i++) {
            InputParameterSpec input = procedure.inputs().get(i);
            Object value = parameters != null ? parameters.get(input.getName()) : null;
            if (value == null && input.isRequiredOrDefault(true)) {
                throw new IllegalArgumentException("Missing required input '" + input.getName()
                        + "' for SQL procedure " + where);
            }
            int position = procedure.positions().get(i);
            binds.add(SqlBind.of(input, value, where, position, procedure.inoutType(position)));
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
        if (!procedure.readOnly()) {
            return executeWrite(current, procedure, binds);
        }
        try (Connection connection = current.getConnection()) {
            return read(connection, procedure, binds);
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
     * Run one read on a pooled connection. A procedure with a cursor {@code OUT} runs in a
     * transaction, rolled back once its rows are read: PostgreSQL closes a {@code refcursor} at
     * the end of the transaction, which in auto-commit is the call itself. HikariCP restores
     * auto-commit when the connection returns to the pool. A rollback that fails evicts the
     * connection and fails the call. Package-private for tests.
     */
    ConsumedResult read(Connection connection, Procedure procedure, List<SqlBind> binds)
            throws SQLException {
        readOnly(connection);
        boolean cursor = returnsCursor(procedure);
        if (cursor) {
            connection.setAutoCommit(false);
        }
        ConsumedResult result;
        try {
            result = readStatement(connection, procedure, binds);
        } catch (SQLException | RuntimeException e) {
            if (cursor) {
                endCursorTransaction(connection, procedure, e);
            }
            throw e;
        }
        if (cursor) {
            endCursorTransaction(connection, procedure, null);
        }
        return result;
    }

    /**
     * Roll back the transaction a cursor read ran in. When that fails, the connection is evicted,
     * so the pool never hands out a connection still in a transaction, and the rollback error is
     * thrown, or added to the {@code failure} the read already threw.
     */
    private void endCursorTransaction(Connection connection, Procedure procedure,
            Exception failure) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Rollback failed for SQL procedure " + getNamespace() + "."
                    + procedure.name() + "; evicting the connection", e);
            HikariDataSource current = pool;
            if (current != null) {
                current.evictConnection(connection);
            }
            if (failure == null) {
                throw e;
            }
            failure.addSuppressed(e);
        }
    }

    /** @return whether an {@code OUT} parameter is a cursor: {@code REF_CURSOR} or {@code OTHER} */
    static boolean returnsCursor(Procedure procedure) {
        for (SqlOutParameterSpec out : procedure.outs()) {
            String type = out.getSqlType() != null
                    ? out.getSqlType().trim().toUpperCase(Locale.ROOT) : "";
            if ("REF_CURSOR".equals(type) || "OTHER".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private ConsumedResult readStatement(Connection connection, Procedure procedure,
            List<SqlBind> binds) throws SQLException {
        try (PreparedStatement statement = prepare(connection, procedure)) {
            statement.setQueryTimeout(timeoutSeconds(procedure.spec()));
            if (procedure.spec().getFetchSize() != null) {
                statement.setFetchSize(procedure.spec().getFetchSize());
            }
            for (SqlBind bind : binds) {
                bind.bind(statement);
            }
            if (statement instanceof CallableStatement call) {
                registerOuts(call, procedure);
            }
            ObjectNode document;
            if (firstResultSet(statement)) {
                try (ResultSet rows = statement.getResultSet()) {
                    document = SqlRowDocument.read(rows);
                }
            } else {
                document = SqlRowDocument.empty();
            }
            drainResults(statement);
            if (statement instanceof CallableStatement call && !procedure.outs().isEmpty()) {
                ObjectNode out = document.putObject(SqlRowDocument.OUT);
                for (SqlOutParameterSpec param : procedure.outs()) {
                    out.set(param.getName(), SqlRowDocument.out(call, param.getPosition(),
                            JDBCType.valueOf(param.getSqlType().trim()
                                    .toUpperCase(Locale.ROOT)), param.getName()));
                }
            }
            return SqlConsumedResult.success(procedure.view(), document);
        }
    }

    /**
     * Read past the remaining results and update counts. SQL Server reports an error raised after
     * a procedure's first result set only when the results are walked, and fills OUT values only
     * after the last one: closing the statement first would report success.
     */
    static void drainResults(PreparedStatement statement) throws SQLException {
        while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
            // the rows of later result sets are not returned
        }
    }

    /**
     * Execute and move to the first result set, skipping update counts: some drivers (HSQLDB,
     * SQL Server) report a stored procedure's result set only after {@code getMoreResults()}.
     *
     * @return whether a result set is current
     */
    static boolean firstResultSet(PreparedStatement statement) throws SQLException {
        boolean resultSet = statement.execute();
        while (!resultSet && statement.getUpdateCount() != -1) {
            resultSet = statement.getMoreResults();
        }
        return resultSet;
    }

    /**
     * Run one write call as one transaction: commit when it succeeds, roll back on any error, and
     * roll back when it changed more rows than {@code maxAffectedRows}. The engine never retries.
     *
     * <p>Auto-commit is not switched back here: HikariCP restores it when the connection returns
     * to the pool, after rolling back anything left uncommitted. Switching it back by hand would
     * commit the open transaction when a rollback had failed. A connection whose rollback failed
     * is evicted from the pool.</p>
     */
    ConsumedResult executeWrite(HikariDataSource current, Procedure procedure,
            List<SqlBind> binds) {
        try (Connection connection = current.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ConsumedResult result = runWrite(connection, procedure, binds);
                if (result.isSuccessful()) {
                    connection.commit();
                } else if (!rollback(connection, procedure)) {
                    current.evictConnection(connection);
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                if (!rollback(connection, procedure)) {
                    current.evictConnection(connection);
                }
                throw e;
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

    /** Run a write on a connection whose transaction the caller owns. Package-private for tests. */
    ConsumedResult runWrite(Connection connection, Procedure procedure,
            List<SqlBind> binds) throws SQLException {
        try (PreparedStatement statement = prepare(connection, procedure)) {
            statement.setQueryTimeout(timeoutSeconds(procedure.spec()));
            if (procedure.spec().getFetchSize() != null) {
                // RETURNING, OUTPUT and a write procedure's rows are fetched as a read's are
                statement.setFetchSize(procedure.spec().getFetchSize());
            }
            for (SqlBind bind : binds) {
                bind.bind(statement);
            }
            if (statement instanceof CallableStatement call) {
                registerOuts(call, procedure);
            }
            // Walk every result: the first result set (RETURNING, OUTPUT, a procedure's rows)
            // becomes the rows, and the update counts add up to the rows changed
            ObjectNode document = null;
            long changed = 0;
            boolean counted = false;
            boolean resultSet = statement.execute();
            while (true) {
                if (resultSet) {
                    try (ResultSet rows = statement.getResultSet()) {
                        if (document == null) {
                            document = SqlRowDocument.read(rows);
                        }
                    }
                } else {
                    int count = statement.getUpdateCount();
                    if (count == -1) {
                        break;
                    }
                    changed += count;
                    counted = true;
                }
                resultSet = statement.getMoreResults();
            }
            if (document == null) {
                document = SqlRowDocument.empty();
            }
            long rowCount = counted ? changed : document.get(SqlRowDocument.ROW_COUNT).asLong();
            document.put(SqlRowDocument.ROW_COUNT, rowCount);
            Integer max = procedure.spec().getMaxAffectedRows();
            if (max != null && rowCount > max) {
                LOGGER.warning("SQL procedure " + getNamespace() + "." + procedure.name()
                        + " would change " + rowCount + " rows, more than maxAffectedRows " + max
                        + "; rolled back");
                return SqlConsumedResult.tooManyRows(procedure.view(), rowCount, max);
            }
            if (procedure.generatedKeys()) {
                // Only a driver that cannot return keys is tolerated: an error while reading
                // them fails the call, so the write rolls back
                ResultSet keys = null;
                try {
                    keys = statement.getGeneratedKeys();
                } catch (java.sql.SQLFeatureNotSupportedException noKeys) {
                    LOGGER.log(Level.FINE, "No generated keys for SQL procedure {0}",
                            procedure.name());
                }
                if (keys != null) {
                    try (ResultSet read = keys) {
                        document.set(SqlRowDocument.GENERATED_KEYS,
                                SqlRowDocument.read(read).get(SqlRowDocument.ROWS));
                    }
                }
            }
            if (statement instanceof CallableStatement call && !procedure.outs().isEmpty()) {
                ObjectNode out = document.putObject(SqlRowDocument.OUT);
                for (SqlOutParameterSpec param : procedure.outs()) {
                    out.set(param.getName(), SqlRowDocument.out(call, param.getPosition(),
                            JDBCType.valueOf(param.getSqlType().trim()
                                    .toUpperCase(Locale.ROOT)), param.getName()));
                }
            }
            return SqlConsumedResult.success(procedure.view(), document);
        }
    }

    /** @return whether the rollback succeeded */
    boolean rollback(Connection connection, Procedure procedure) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Rollback failed for SQL procedure " + getNamespace() + "."
                    + procedure.name() + "; evicting the connection", e);
            return false;
        }
    }

    /** Prepare the statement or the stored-procedure call of a procedure. */
    static PreparedStatement prepare(Connection connection, Procedure procedure)
            throws SQLException {
        if (procedure.isStored()) {
            return connection.prepareCall(procedure.jdbcSql());
        }
        if (procedure.generatedKeys()) {
            return connection.prepareStatement(procedure.jdbcSql(),
                    java.sql.Statement.RETURN_GENERATED_KEYS);
        }
        return connection.prepareStatement(procedure.jdbcSql());
    }

    private void registerOuts(CallableStatement call, Procedure procedure) throws SQLException {
        String where = getNamespace() + "." + procedure.name();
        for (SqlOutParameterSpec out : procedure.outs()) {
            call.registerOutParameter(out.getPosition(), SqlBind.jdbcType(out.getSqlType(),
                    "out parameter '" + out.getName() + "'", where).getVendorTypeNumber());
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
        properties.forEach(config::addDataSourceProperty);
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
