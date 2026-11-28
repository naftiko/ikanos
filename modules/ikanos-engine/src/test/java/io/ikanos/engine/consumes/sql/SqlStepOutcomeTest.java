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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.execution.StepOutcomes;

/** The protocol-neutral outcome of a SQL call, which its status alone does not tell (#1168). */
class SqlStepOutcomeTest {

    private static String outcomeOf(SQLException e) {
        return StepOutcomes.of(SqlConsumedResult.failure(ConsumedOperationView.NONE, e));
    }

    @Test
    void aConnectionFailureShouldBeUnreachable() {
        assertEquals("unreachable", outcomeOf(new SQLException("refused", "08001", 0)));
    }

    @Test
    void aTimeoutShouldBeUnreachable() {
        assertEquals("unreachable", outcomeOf(new SQLTimeoutException("slow")));
    }

    @Test
    void aSyntaxErrorShouldBeAFailureThoughItIsAlso502() {
        assertEquals("failure", outcomeOf(new SQLException("bad", "42601", 0)));
    }

    @Test
    void anIntegrityViolationShouldBeAFailure() {
        assertEquals("failure", outcomeOf(new SQLException("dup", "23505", 0)));
    }

    @Test
    void aSuccessfulCallShouldBeASuccess() {
        assertEquals("success", StepOutcomes.of(
                SqlConsumedResult.success(ConsumedOperationView.NONE, null)));
    }
}
