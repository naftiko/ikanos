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

import java.util.List;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.observability.OtelNullSafety;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * One prepared SQL call: the procedure and its bound values, built by
 * {@link SqlClientAdapter#prepare}. Nothing touches the database until {@link #invoke()}.
 *
 * <p>The client span is named after the operation ({@code SELECT} or {@code CALL}) and the database
 * ({@code <system>://<host>/<database>}), and carries the OpenTelemetry database attributes. It
 * never carries the query text or a bound value.</p>
 */
final class SqlInvocation extends ConsumedInvocation {

    static final AttributeKey<String> ATTR_DB_SYSTEM = AttributeKey.stringKey("db.system.name");
    static final AttributeKey<String> ATTR_DB_NAMESPACE = AttributeKey.stringKey("db.namespace");
    static final AttributeKey<String> ATTR_DB_OPERATION =
            AttributeKey.stringKey("db.operation.name");
    static final AttributeKey<String> ATTR_DB_STORED_PROCEDURE =
            AttributeKey.stringKey("db.stored_procedure.name");
    static final AttributeKey<String> ATTR_DB_PROCEDURE =
            AttributeKey.stringKey("ikanos.sql.procedure");
    static final AttributeKey<String> ATTR_DB_STATUS =
            AttributeKey.stringKey("db.response.status_code");

    private final SqlClientAdapter adapter;
    private final SqlClientAdapter.Procedure procedure;
    private final List<SqlBind> binds;

    SqlInvocation(SqlClientAdapter adapter, SqlClientAdapter.Procedure procedure,
            List<SqlBind> binds) {
        this.adapter = adapter;
        this.procedure = procedure;
        this.binds = binds;
    }

    /** @return the values bound, in placeholder order (for tests) */
    List<SqlBind> binds() {
        return binds;
    }

    @Override
    public String namespace() {
        return adapter.getNamespace();
    }

    @Override
    public ConsumedOperationView operation() {
        return procedure.view();
    }

    @Override
    protected ClientSpanDescriptor spanDescriptor() {
        return new ClientSpanDescriptor(procedure.operation(), adapter.jdbcUrl().spanTarget());
    }

    @Override
    protected ConsumedResult doInvoke() {
        Span span = Span.current();
        JdbcUrl url = adapter.jdbcUrl();
        span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_SYSTEM), url.system());
        if (url.database() != null) {
            span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_NAMESPACE), url.database());
        }
        span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_OPERATION),
                procedure.operation());
        span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_PROCEDURE), procedure.name());
        if (procedure.isStored()) {
            span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_STORED_PROCEDURE),
                    procedure.spec().getStoredProcedure().trim());
        }
        return adapter.execute(procedure, binds);
    }

    /**
     * Mark the span as failed on an error, with the SQL-equivalent status. The HTTP status
     * attribute the default sets would mislabel a database call.
     */
    @Override
    protected void annotateSpan(Span span, ConsumedResult result) {
        if (result.outcome() != Outcome.SUCCESS) {
            if (result.failureCause() instanceof java.sql.SQLException e
                    && e.getSQLState() != null) {
                span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_DB_STATUS),
                        e.getSQLState());
            }
            span.setStatus(StatusCode.ERROR, "status " + result.status());
        }
    }
}
