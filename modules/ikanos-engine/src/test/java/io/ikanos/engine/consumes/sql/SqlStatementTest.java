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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlStatementTest {

    @Test
    void parseShouldRewriteNamedPlaceholdersInOrder() {
        SqlStatement statement =
                SqlStatement.parse("select * from t where a = :first and b > :second");

        assertEquals("select * from t where a = ? and b > ?", statement.jdbcSql());
        assertEquals(List.of("first", "second"), statement.placeholders());
    }

    @Test
    void parseShouldBindARepeatedPlaceholderOncePerUse() {
        SqlStatement statement = SqlStatement.parse("select * from t where a = :id or b = :id");

        assertEquals(List.of("id", "id"), statement.placeholders());
    }

    @Test
    void parseShouldKeepPlaceholdersInsideStringLiteralsAsText() {
        SqlStatement statement =
                SqlStatement.parse("select ':notme', 'it''s :notme' from t where a = :yes");

        assertEquals(List.of("yes"), statement.placeholders());
        assertEquals("select ':notme', 'it''s :notme' from t where a = ?", statement.jdbcSql());
    }

    @Test
    void parseShouldIgnorePlaceholdersInQuotedIdentifiersAndComments() {
        SqlStatement statement = SqlStatement.parse("""
                select "col:x", `y:z` -- :comment
                from t /* :block */ where a = :yes""");

        assertEquals(List.of("yes"), statement.placeholders());
    }

    @Test
    void parseShouldKeepPostgresCastsAndDollarQuotes() {
        SqlStatement statement = SqlStatement.parse(
                "select :since::timestamp, $$ :inside $$, $tag$ :x $tag$, $1 from t");

        assertEquals(List.of("since"), statement.placeholders());
        assertEquals("select ?::timestamp, $$ :inside $$, $tag$ :x $tag$, $1 from t",
                statement.jdbcSql());
    }

    @Test
    void parseShouldHonourBackslashEscapesInEscapeStrings() {
        SqlStatement statement = SqlStatement.parse("select E'a\\' :no' from t where a = :yes");

        assertEquals(List.of("yes"), statement.placeholders());
    }

    @Test
    void parseShouldDropATrailingSemicolon() {
        SqlStatement statement = SqlStatement.parse("select 1;  -- done\n");

        assertFalse(statement.jdbcSql().contains(";"));
    }

    @Test
    void parseShouldRefuseASecondStatement() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select 1; delete from t"));

        assertTrue(e.getMessage().contains("more than one SQL statement"), e.getMessage());
    }

    @Test
    void parseShouldRefuseAnUnterminatedLiteralWithoutQuotingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select 'secret from t"));

        assertFalse(e.getMessage().contains("secret"), e.getMessage());
    }

    @Test
    void nonReadKeywordShouldBeNullForSelectWithAndValues() {
        assertNull(SqlStatement.parse("select 1").nonReadKeyword());
        assertNull(SqlStatement.parse("WITH x AS (select 1) select * from x").nonReadKeyword());
        assertNull(SqlStatement.parse("values (1)").nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldNameAStatementThatDoesNotStartWithARead() {
        assertEquals("DELETE", SqlStatement.parse("delete from t").nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldCatchADataModifyingCte() {
        assertEquals("DELETE", SqlStatement
                .parse("with d as (delete from t returning *) select * from d")
                .nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldCatchSelectInto() {
        assertEquals("INTO", SqlStatement.parse("select * into copy from t").nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldIgnoreKeywordsInLiteralsAndOrdinaryColumns() {
        assertNull(SqlStatement
                .parse("select comment, replace(name, 'a', 'b'), 'delete' from t")
                .nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldReadADollarSignAsPartOfAnIdentifier() {
        for (SqlStatement.Dialect dialect : SqlStatement.Dialect.values()) {
            SqlStatement statement =
                    SqlStatement.parse("select audit$delete, a$b$ from events", dialect);

            assertNull(statement.nonReadKeyword(), dialect.name());
            assertEquals("select audit$delete, a$b$ from events", statement.jdbcSql(),
                    dialect.name());
        }
    }

    @Test
    void parseShouldReadMysqlBackslashEscapesSoASecondStatementCannotHide() {
        String sql = "select 'a\\'' from t; delete from t; -- '";

        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(sql, true));
    }

    @Test
    void parseShouldReadAMysqlHashCommentSoASecondStatementCannotHide() {
        String sql = "select 1 # '\n; delete from t; -- '";

        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(sql, true));
    }

    @Test
    void parseShouldReadMysqlDoubleDashWithoutSpaceAsArithmetic() {
        String sql = "select 1--1 from t; delete from t";

        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(sql, true));
    }

    @Test
    void parseShouldKeepStandardRulesOutsideMysql() {
        assertEquals(List.of("id"),
                SqlStatement.parse("select '#', $$:x$$ from t where id = :id", false)
                        .placeholders());
    }

    @Test
    void nonReadKeywordShouldRefuseASqlServerStatementWithoutASeparator() {
        assertEquals("DROP", SqlStatement.parse("SELECT 1 DROP TABLE customers",
                SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
        assertEquals("EXEC", SqlStatement.parse("select 1 exec sp_who",
                SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldAcceptAnOffsetFetchReadOnSqlServer() {
        assertNull(SqlStatement.parse("select id from t order by id offset 10 rows"
                + " fetch next 5 rows only", SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
    }

    @Test
    void parseShouldCountNestedBlockCommentsOnPostgresql() {
        SqlStatement statement = SqlStatement.parse(
                "select /* outer /* inner */ :ignored */ :id", SqlStatement.Dialect.POSTGRESQL);

        assertEquals(List.of("id"), statement.placeholders());
    }

    @Test
    void parseShouldNotNestBlockCommentsOutsideDialectsThatDo() {
        assertEquals(List.of("x"), SqlStatement.parse("select /* a /* b */ :x",
                SqlStatement.Dialect.STANDARD).placeholders());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select /* a /* b */ 1", SqlStatement.Dialect.POSTGRESQL));
    }

    @Test
    void parseShouldReadBracketIdentifiersOnSqlServerAndSqlite() {
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.SQLSERVER, SqlStatement.Dialect.SQLITE)) {
            SqlStatement statement = SqlStatement.parse(
                    "SELECT [delete], [tag:x], [a]]b] FROM inventory WHERE id = :id", dialect);

            assertNull(statement.nonReadKeyword(), dialect.name());
            assertEquals(List.of("id"), statement.placeholders(), dialect.name());
        }
    }

    @Test
    void parseShouldKeepPostgresqlArrayBrackets() {
        SqlStatement statement = SqlStatement.parse("select tags[:i] from t",
                SqlStatement.Dialect.POSTGRESQL);

        assertEquals(List.of("i"), statement.placeholders());
    }

    @Test
    void parseShouldRefuseMysqlExecutableComments() {
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "SELECT 1 /*!50000 INTO OUTFILE 'export.txt' */", SqlStatement.Dialect.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select 1 /*M! , sleep(10) */", SqlStatement.Dialect.MYSQL));
    }

    @Test
    void parseShouldKeepOrdinaryCommentsAndHintsOnMysql() {
        assertNull(SqlStatement.parse("select /*+ MAX_EXECUTION_TIME(1000) */ id /* note */"
                + " from t", SqlStatement.Dialect.MYSQL).nonReadKeyword());
    }

    @Test
    void parseShouldEndALineCommentAtACarriageReturn() {
        assertEquals("DROP", SqlStatement.parse("SELECT 1 -- note\r DROP TABLE customers",
                SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
        assertEquals(List.of("id"), SqlStatement.parse("select 1 -- note\r where id = :id",
                SqlStatement.Dialect.POSTGRESQL).placeholders());
        assertEquals(List.of(), SqlStatement.parse("select 1 -- note\r :x",
                SqlStatement.Dialect.SQLITE).placeholders());
    }

    @Test
    void parseShouldEscapePostgresqlQuestionMarkOperators() {
        SqlStatement statement = SqlStatement.parse(
                "select id from t where payload ? 'k' and tags ?| array['a'] and id = :id",
                SqlStatement.Dialect.POSTGRESQL);

        assertEquals("select id from t where payload ?? 'k' and tags ??| array['a'] and id = ?",
                statement.jdbcSql());
    }
}
