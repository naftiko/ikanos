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
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ikanos.spec.OperationSpec;

/**
 * One SQL procedure, keyed under {@code consumes[type=sql].procedures} by its name. A step reaches
 * it with {@code call: <namespace>.<name>}.
 *
 * <p>Extends {@link OperationSpec} to reuse {@code name}, {@code description},
 * {@code inputParameters} (map form, bound as JDBC bind variables) and consumed
 * {@code outputParameters} (JsonPath {@code value} mappings on the row document). The HTTP-only
 * {@code method}, {@code outputRawFormat} and {@code outputSchema} fields are inherited but
 * unused, as on {@code McpClientToolSpec}.</p>
 */
public class SqlProcedureSpec extends OperationSpec {

    /** Default {@code timeoutMs}: 30 seconds. */
    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final AtomicReference<String> statement = new AtomicReference<>();
    private final AtomicReference<String> storedProcedure = new AtomicReference<>();
    private final AtomicReference<Boolean> readOnly = new AtomicReference<>();
    private final Map<String, SqlOutParameterSpec> outParameters =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicReference<Integer> timeoutMs = new AtomicReference<>();
    private final AtomicReference<Integer> fetchSize = new AtomicReference<>();

    public SqlProcedureSpec() {
        super();
    }

    /** @return the inline SQL, with {@code :name} placeholders, or {@code null} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getStatement() {
        return statement.get();
    }

    public void setStatement(String statement) {
        this.statement.set(statement);
    }

    /** @return the stored procedure or function to call by name, or {@code null} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getStoredProcedure() {
        return storedProcedure.get();
    }

    public void setStoredProcedure(String storedProcedure) {
        this.storedProcedure.set(storedProcedure);
    }

    /**
     * Whether the procedure only reads. Defaults to {@code true} for a statement, which is
     * checked; a stored procedure is opaque to the engine, so it must say.
     *
     * @return the declared value, or {@code null} when absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getReadOnly() {
        return readOnly.get();
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly.set(readOnly);
    }

    /** @return stored-procedure {@code OUT} and {@code INOUT} parameters, keyed by name */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, SqlOutParameterSpec> getOutParameters() {
        return outParameters;
    }

    public void setOutParameters(Map<String, SqlOutParameterSpec> values) {
        outParameters.clear();
        if (values != null) {
            values.forEach((name, out) -> {
                out.setName(name);
                outParameters.put(name, out);
            });
        }
    }

    /** @return the query timeout in milliseconds, or {@code null} for the default */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getTimeoutMs() {
        return timeoutMs.get();
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs.set(timeoutMs);
    }

    /** @return the JDBC fetch size hint, or {@code null} to keep the driver's */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getFetchSize() {
        return fetchSize.get();
    }

    public void setFetchSize(Integer fetchSize) {
        this.fetchSize.set(fetchSize);
    }
}
