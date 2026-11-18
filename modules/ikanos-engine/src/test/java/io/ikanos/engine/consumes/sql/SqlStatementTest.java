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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlStatementTest {

    @Test
    void parseShouldRewriteNamedPlaceholdersInOrder() {
        SqlStatement statement =
                SqlStatement.parse("select * from t where a = {{first}} and b > {{second}}");

        assertEquals("select * from t where a = ? and b > ?", statement.jdbcSql());
        assertEquals(List.of("first", "second"), statement.placeholders());
    }

    @Test
    void parseShouldTakeAKebabCaseInputNameWithSurroundingSpaces() {
        SqlStatement statement =
                SqlStatement.parse("select * from t where id = {{ customer-id }}");

        assertEquals("select * from t where id = ?", statement.jdbcSql());
        assertEquals(List.of("customer-id"), statement.placeholders());
    }

    @Test
    void parseShouldBindARepeatedPlaceholderOncePerUse() {
        SqlStatement statement =
                SqlStatement.parse("select * from t where a = {{id}} or b = {{id}}");

        assertEquals(List.of("id", "id"), statement.placeholders());
    }

    @Test
    void parseShouldLeaveAColonNameAsText() {
        SqlStatement statement = SqlStatement.parse("select :x, a[1:2] from t");

        assertEquals(List.of(), statement.placeholders());
        assertEquals("select :x, a[1:2] from t", statement.jdbcSql());
    }

    @Test
    void parseShouldRefuseJdbcEscapes() {
        List<String> escapes = List.of("select {fn ucase(name)} from t",
                "select id from t where d > {d '2026-01-01'}",
                "select id from t where at > {ts '2026-01-01 00:00:00'}",
                "select * from {oj a left outer join b on a.id = b.id}", "{call report(1)}");
        for (String sql : escapes) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse(sql), sql);
            assertTrue(e.getMessage().contains("JDBC escape"), e.getMessage());
        }
    }

    @Test
    void parseShouldKeepBracesInsideLiterals() {
        SqlStatement statement =
                SqlStatement.parse("select '{fn x}', '{\"a\": 1}' from t where id = {{id}}");

        assertEquals("select '{fn x}', '{\"a\": 1}' from t where id = ?", statement.jdbcSql());
    }

    @Test
    void parseShouldRefuseAJdbcQuestionMarkOutsidePostgresql() {
        for (SqlStatement.Dialect dialect : SqlStatement.Dialect.values()) {
            if (dialect == SqlStatement.Dialect.POSTGRESQL) {
                continue;
            }
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("select id from t where id = ?", dialect),
                    dialect.name());
            assertTrue(e.getMessage().contains("JDBC ? marker"), e.getMessage());
        }
    }

    @Test
    void parseShouldKeepAQuestionMarkInsideALiteral() {
        assertEquals(List.of(), SqlStatement.parse("select 'why?' from t",
                SqlStatement.Dialect.MYSQL).placeholders());
    }

    @Test
    void parseShouldRefuseAnOracleColonBindMarker() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select id from t where id = :id",
                        SqlStatement.Dialect.ORACLE));

        assertTrue(e.getMessage().contains("Oracle :name bind marker"), e.getMessage());
    }

    @Test
    void parseShouldKeepLiteralsWithoutPlaceholdersAsText() {
        SqlStatement statement = SqlStatement.parse(
                "select ':x', 'it''s', '{{1,2},{3,4}}'::int[] from t where a = {{yes}}");

        assertEquals(List.of("yes"), statement.placeholders());
        assertEquals("select ':x', 'it''s', '{{1,2},{3,4}}'::int[] from t where a = ?",
                statement.jdbcSql());
    }

    @Test
    void parseShouldRefuseAPlaceholderInsideAStringLiteral() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select id from t where name like '%{{text}}%'"));

        assertTrue(e.getMessage().contains("{{text}} inside a string literal"), e.getMessage());
    }

    @Test
    void parseShouldRefuseAPlaceholderInsideAnyQuotedText() {
        List<String> standard = List.of("select \"{{q}}\" from t", "select `{{q}}` from t",
                "select $$ {{q}} $$ from t", "select $tag$ {{ q }} $tag$ from t",
                "select 'a' || '{{x.y}}' from t");
        for (String sql : standard) {
            assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(sql), sql);
        }
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select [{{q}}] from t", SqlStatement.Dialect.SQLSERVER));
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select q'[{{q}}]' from t", SqlStatement.Dialect.ORACLE));
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select \"{{q}}\" from t", SqlStatement.Dialect.MYSQL));
    }

    @Test
    void parseShouldRefuseMustacheTagsOtherThanAName() {
        List<String> tags = List.of("{{#a}}x{{/a}}", "{{^a}}x{{/a}}", "{{{a}}}", "{{&a}}",
                "{{> part}}", "{{! note }}", "{{=<% %>=}}", "{{a.b}}", "{{}}", "{{a b}}");
        for (String tag : tags) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("select id from t where a = " + tag), tag);
        }
    }

    @Test
    void parseShouldRefuseAnUnterminatedPlaceholder() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select id from t where a = {{id"));

        assertTrue(e.getMessage().contains("unterminated {{"), e.getMessage());
    }

    @Test
    void parseShouldIgnorePlaceholdersInComments() {
        SqlStatement statement = SqlStatement.parse("""
                select "col:x", `y:z` -- {{comment}}
                from t /* {{block}} */ where a = {{yes}}""");

        assertEquals(List.of("yes"), statement.placeholders());
    }

    @Test
    void parseShouldKeepPostgresCastsAndDollarQuotes() {
        SqlStatement statement = SqlStatement.parse(
                "select {{since}}::timestamp, $$ :inside $$, $tag$ :x $tag$, $1 from t");

        assertEquals(List.of("since"), statement.placeholders());
        assertEquals("select ?::timestamp, $$ :inside $$, $tag$ :x $tag$, $1 from t",
                statement.jdbcSql());
    }

    @Test
    void parseShouldHonourBackslashEscapesInEscapeStrings() {
        // read as ending at \', the literal would leave {{no}} outside, then an unterminated '
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("select E'a\\' {{no}}' from t"));

        assertTrue(e.getMessage().contains("{{no}} inside a string literal"), e.getMessage());
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
    void writeOperationShouldRefuseADataModifyingCteWhoseRowsTheDriverWouldNotCount() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("with d as (delete from t where id > 0 returning *)"
                        + " select count(*) from d").writeOperation());

        assertTrue(e.getMessage().contains("inside a WITH"), e.getMessage());
    }

    @Test
    void writeOperationShouldAcceptAWriteAfterAWithThatOnlyReads() {
        assertEquals("UPDATE", SqlStatement.parse("with x as (select id from s where flag)"
                + " update t set n = 0 where id in (select id from x)").writeOperation());
    }

    @Test
    void writeOperationShouldRefuseTwoWrites() {
        assertThrows(IllegalArgumentException.class, () -> SqlStatement
                .parse("with d as (delete from t where id = {{id}}) insert into log values (1)")
                .writeOperation());
    }

    @Test
    void writeOperationShouldRefuseADeleteAfterAQuotedIdentifierThatFollowsFor() {
        // NEXT VALUE FOR [seq]: FOR is not directly before DELETE, which starts a second statement
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "INSERT INTO audit (seqno) VALUES (NEXT VALUE FOR [seq]) DELETE FROM orders",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
    }

    @Test
    void writeOperationShouldReadAClauseAcrossAComment() {
        assertEquals("INSERT", SqlStatement.parse("insert into t (id, n) values ({{id}}, 1)"
                + " on conflict (id) do /* update existing */ update set n = t.n + 1",
                SqlStatement.Dialect.POSTGRESQL).writeOperation());
        assertEquals("MERGE", SqlStatement.parse("merge into t using s on t.id = s.id"
                + " when matched then -- refresh\n update set t.n = s.n",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
        assertEquals("INSERT", SqlStatement.parse("insert into t (id, n) values ({{id}}, 1)"
                + " on duplicate key # bump\n update n = n + 1",
                SqlStatement.Dialect.MYSQL).writeOperation());
    }

    @Test
    void writeOperationShouldAcceptAnUpsertWhoseUpdateIsAClause() {
        assertEquals("INSERT", SqlStatement.parse(
                "insert into t (id, n) values ({{id}}, 1) on duplicate key update n = n + 1")
                .writeOperation());
    }

    @Test
    void writeOperationShouldRefuseAWithThatOnlyReads() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("with x as (select 1) select * from x").writeOperation());
    }

    @Test
    void writeOperationShouldRefuseAnUpdateWhoseOnlyWhereIsInASubquery() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("update t set n = (select max(n) from s where s.id = 1)")
                        .writeOperation());

        assertTrue(e.getMessage().contains("without WHERE"), e.getMessage());
    }

    @Test
    void writeOperationShouldAcceptAnOnConflictDoUpdateUpsert() {
        assertEquals("INSERT", SqlStatement.parse("insert into t (id, n) values ({{id}}, 1)"
                + " on conflict (id) do update set n = t.n + 1").writeOperation());
    }

    @Test
    void writeOperationShouldRefuseACallToAProcedure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("call settle_invoices()").writeOperation());

        assertTrue(e.getMessage().contains("CALL"), e.getMessage());
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
                SqlStatement.parse("select '#', $$:x$$ from t where id = {{id}}", false)
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
    void nonReadKeywordShouldRefuseATriggerStatementAppendedToASqlServerRead() {
        assertEquals("DISABLE", SqlStatement.parse(
                "SELECT 1 WHERE 1=1 DISABLE TRIGGER ALL ON dbo.customers",
                SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
        assertEquals("ENABLE", SqlStatement.parse(
                "select 1 enable /* audit */ trigger audit on customers",
                SqlStatement.Dialect.SQLSERVER).nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldAcceptEnableAndDisableAsSqlServerColumns() {
        assertNull(SqlStatement.parse(
                "select enable, disable from features where id = {{id}}",
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
                "select /* outer /* inner */ {{ignored}} */ {{id}}",
                SqlStatement.Dialect.POSTGRESQL);

        assertEquals(List.of("id"), statement.placeholders());
    }

    @Test
    void parseShouldNotNestBlockCommentsOutsideDialectsThatDo() {
        assertEquals(List.of("x"), SqlStatement.parse("select /* a /* b */ {{x}}",
                SqlStatement.Dialect.STANDARD).placeholders());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select /* a /* b */ 1", SqlStatement.Dialect.POSTGRESQL));
    }

    @Test
    void parseShouldReadBracketIdentifiersOnSqlServerAndSqlite() {
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.SQLSERVER, SqlStatement.Dialect.SQLITE)) {
            SqlStatement statement = SqlStatement.parse(
                    "SELECT [delete], [tag:x], [a]]b] FROM inventory WHERE id = {{id}}", dialect);

            assertNull(statement.nonReadKeyword(), dialect.name());
            assertEquals(List.of("id"), statement.placeholders(), dialect.name());
        }
    }

    @Test
    void parseShouldReadOracleAlternativeQuotedLiterals() {
        SqlStatement statement = SqlStatement.parse(
                "select q'[don't :ignored]', nQ'{a'b :x}', Q'!:y!' from customers where id = {{id}}",
                SqlStatement.Dialect.ORACLE);

        assertEquals(List.of("id"), statement.placeholders());
        assertNull(statement.nonReadKeyword());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select q'[open :x from t", SqlStatement.Dialect.ORACLE));
    }

    @Test
    void parseShouldReadQAsAnIdentifierOutsideOracle() {
        assertEquals(List.of("id"), SqlStatement.parse("select q from t where id = {{id}}",
                SqlStatement.Dialect.ORACLE).placeholders());
        // elsewhere q is an identifier and ':x' an ordinary literal
        assertEquals(List.of(), SqlStatement.parse("select q':x' from t",
                SqlStatement.Dialect.STANDARD).placeholders());
    }

    @Test
    void parseShouldKeepPostgresqlArrayBrackets() {
        SqlStatement statement = SqlStatement.parse("select tags[{{i}}] from t",
                SqlStatement.Dialect.POSTGRESQL);

        assertEquals(List.of("i"), statement.placeholders());
        assertEquals("select tags[?] from t", statement.jdbcSql());
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
    void parseShouldEndALineCommentAtACarriageReturnOnPostgresql() {
        assertEquals(List.of("id"), SqlStatement.parse("select 1 -- note\r where id = {{id}}",
                SqlStatement.Dialect.POSTGRESQL).placeholders());
    }

    @Test
    void parseShouldRefuseALineCommentEndedByACarriageReturnAloneElsewhere() {
        // MySQL reads "# \r'" on to the line feed: INTO OUTFILE would run outside the quotes
        String mysql = "select 1 # \r' \n into outfile '/tmp/x' -- '";
        assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse(mysql, SqlStatement.Dialect.MYSQL));
        for (SqlStatement.Dialect dialect : List.of(SqlStatement.Dialect.SQLSERVER,
                SqlStatement.Dialect.SQLITE, SqlStatement.Dialect.ORACLE,
                SqlStatement.Dialect.STANDARD)) {
            assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                    "SELECT 1 -- note\r DROP TABLE customers", dialect), dialect.name());
        }
    }

    @Test
    void parseShouldAcceptALineCommentEndedByCarriageReturnAndLineFeed() {
        for (SqlStatement.Dialect dialect : SqlStatement.Dialect.values()) {
            assertEquals(List.of("id"), SqlStatement.parse(
                    "select 1 -- note\r\nwhere id = {{id}}", dialect).placeholders(),
                    dialect.name());
        }
    }

    @Test
    void parseShouldReadMysqlDoubleDashBeforeAControlCharacterAsAComment() {
        // MySQL starts a comment at "--" before any control character, not only whitespace
        String sql = "select 1 --\u0001 '\n into outfile '/tmp/x' -- '\nfrom t";

        assertEquals("INTO", SqlStatement.parse(sql, SqlStatement.Dialect.MYSQL)
                .nonReadKeyword());
    }

    @Test
    void parseShouldReadMysqlDoubleDashBeforeAUnicodeSpaceAsArithmetic() {
        // MySQL does not take U+2003 as the space a comment needs: the INTO is code there
        String sql = "select 1 from t where 1 --  into outfile '/tmp/x'";

        assertEquals("INTO", SqlStatement.parse(sql, SqlStatement.Dialect.MYSQL)
                .nonReadKeyword());
    }

    @Test
    void parseShouldRefuseABackslashEscapedQuoteOnMysql() {
        // under NO_BACKSLASH_ESCAPES, 'a\' closes and INTO OUTFILE runs outside the quotes
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select 'a\\' into outfile '/tmp/x' -- ' from t", SqlStatement.Dialect.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "select \"a\\\" from t", SqlStatement.Dialect.MYSQL));
        assertNull(SqlStatement.parse("select 'it''s', 'a\\\\', 'a\\%' from t",
                SqlStatement.Dialect.MYSQL).nonReadKeyword());
    }

    @Test
    void nonReadKeywordShouldRefuseServiceBrokerAndSignatureStatementsOnSqlServer() {
        for (String sql : List.of("select 1 as a add signature to dbo.p by certificate c",
                "select 1 as a receive top (1) * from dbo.q",
                "select 1 as a send on conversation 'h' ('m')",
                "select 1 as a end conversation 'h'",
                "select 1 as a move conversation 'h' to 'g'")) {
            assertNotNull(SqlStatement.parse(sql, SqlStatement.Dialect.SQLSERVER)
                    .nonReadKeyword(), sql);
        }
    }

    @Test
    void nonReadKeywordShouldAcceptCaseEndAndSendAsSqlServerColumns() {
        assertNull(SqlStatement.parse("select case when send = 1 then 'y' end as sent, send"
                + " from mail where id = {{id}}", SqlStatement.Dialect.SQLSERVER)
                .nonReadKeyword());
    }

    @Test
    void parseShouldEscapePostgresqlQuestionMarkOperators() {
        SqlStatement statement = SqlStatement.parse(
                "select id from t where payload ? 'k' and tags ?| array['a'] and id = {{id}}",
                SqlStatement.Dialect.POSTGRESQL);

        assertEquals("select id from t where payload ?? 'k' and tags ??| array['a'] and id = ?",
                statement.jdbcSql());
    }

    @Test
    void writeOperationShouldAcceptAnOracleMergeWithADeleteWhereClause() {
        assertEquals("MERGE", SqlStatement.parse("merge into t using s on (t.id = s.id)"
                + " when matched then update set t.n = s.n delete where t.n = 0"
                + " when not matched then insert (id, n) values (s.id, s.n)").writeOperation());
    }

    @Test
    void writeOperationShouldStillRefuseADeleteAfterAWriteThatIsNotAMerge() {
        assertThrows(IllegalArgumentException.class, () -> SqlStatement
                .parse("update t set n = 0 where id = 1 delete where id = 2").writeOperation());
    }

    @Test
    void returnsRowsShouldReadAnOutputClauseButNotAColumnNamedOutput() {
        SqlStatement.Dialect sqlServer = SqlStatement.Dialect.SQLSERVER;
        assertFalse(SqlStatement.parse("insert into audit (output) values ({{value}})", sqlServer)
                .returnsRows());
        assertTrue(SqlStatement.parse("insert into t (a) output inserted.id values (1)",
                sqlServer).returnsRows());
        assertTrue(SqlStatement.parse("merge into t using s on t.id = s.id when matched then"
                + " delete output $action, deleted.id;", sqlServer).returnsRows());
        assertTrue(SqlStatement.parse("insert into t (a) values (1) returning id")
                .returnsRows());
    }

    @Test
    void returnsRowsShouldNotReadOutputColumnsAsAnOutputClause() {
        for (SqlStatement.Dialect dialect : SqlStatement.Dialect.values()) {
            assertFalse(SqlStatement.parse("insert into audit (output, inserted)"
                    + " values ({{output}}, {{inserted}})", dialect).returnsRows(), dialect.name());
            assertFalse(SqlStatement.parse("insert into audit select output, inserted from x",
                    dialect).returnsRows(), dialect.name());
        }
        assertFalse(SqlStatement.parse("insert into t (a) output inserted.id values (1)",
                SqlStatement.Dialect.POSTGRESQL).returnsRows());
    }

    @Test
    void returnsRowsShouldNotReadAColumnNamedReturningAsAReturningClause() {
        for (SqlStatement.Dialect dialect : SqlStatement.Dialect.values()) {
            assertFalse(SqlStatement.parse("insert into audit (returning) values ({{value}})",
                    dialect).returnsRows(), dialect.name());
        }
        // SQL Server has no RETURNING clause: there it is always a name
        assertFalse(SqlStatement.parse("insert into audit select a from s returning",
                SqlStatement.Dialect.SQLSERVER).returnsRows());
        assertTrue(SqlStatement.parse("insert into t (a) values (1) returning id",
                SqlStatement.Dialect.POSTGRESQL).returnsRows());
    }

    @Test
    void writeOperationShouldRefuseASecondSqlServerStatementThatSelects() {
        for (String sql : List.of(
                "update orders set status = 'SHIPPED' select id into orders_copy from orders"
                        + " where id = 1",
                "insert into t (a) values (1) select a into c from t",
                "insert into t select 1 select a into c from t",
                "delete from t where id = 1 select * from t")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse(sql, SqlStatement.Dialect.SQLSERVER)
                            .writeOperation(), sql);
        }
    }

    @Test
    void writeOperationShouldAcceptAnInsertFromASelectOnSqlServer() {
        for (String sql : List.of(
                "insert into t (a) select a from s where id = {{id}}",
                "insert into t select a from s union all select a from u",
                "update t set a = (select max(a) from s) where id = 1",
                "with src as (select a from s) insert into t select a from src")) {
            assertEquals(sql.substring(0, 6).equals("update") ? "UPDATE" : "INSERT",
                    SqlStatement.parse(sql, SqlStatement.Dialect.SQLSERVER).writeOperation(),
                    sql);
        }
    }

    @Test
    void writeOperationShouldRefuseAStatementAppendedWithoutASeparator() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("UPDATE orders SET status = 'SHIPPED'"
                        + " WHERE region = 'EU' COMMIT").writeOperation());

        assertTrue(e.getMessage().contains("COMMIT"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement
                .parse("delete from orders where id = 1 drop table orders").writeOperation());
    }

    @Test
    void writeOperationShouldRefuseASqlServerStatementWithoutASeparator() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("update orders set status = 'SHIPPED' where id = 1"
                        + " print 'done'", SqlStatement.Dialect.SQLSERVER).writeOperation());

        assertTrue(e.getMessage().contains("PRINT"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "insert into t (a) values (1) declare @x int", SqlStatement.Dialect.SQLSERVER)
                .writeOperation());
        // only the SET of an UPDATE belongs to it
        e = assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "update t set a = 1 where id = 1 set nocount on", SqlStatement.Dialect.SQLSERVER)
                .writeOperation());
        assertTrue(e.getMessage().contains("SET"), e.getMessage());
    }

    @Test
    void writeOperationShouldRefuseTriggerDdlAppendedToASqlServerWrite() {
        for (String keyword : List.of("DISABLE", "ENABLE")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("update orders set status = 'SHIPPED' where id = 1 "
                            + keyword.toLowerCase() + " trigger audit on orders",
                            SqlStatement.Dialect.SQLSERVER).writeOperation());

            assertTrue(e.getMessage().contains(keyword), e.getMessage());
        }
        assertEquals("UPDATE", SqlStatement.parse("update t set \"disable\" = 1 where id = 1",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
    }

    @Test
    void writeOperationShouldRefuseServiceBrokerStatementsAppendedToASqlServerWrite() {
        for (String appended : List.of("receive * from q", "send on conversation @h ('x')",
                "end conversation @h", "move conversation @h to @g",
                "add signature to p by certificate c")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("update orders set status = 'SHIPPED' where id = 1 "
                            + appended, SqlStatement.Dialect.SQLSERVER).writeOperation(),
                    appended);

            assertTrue(e.getMessage().contains("on SQL Server it also runs"), e.getMessage());
        }
        // alone, or ending a CASE, the first words stay usable
        assertEquals("UPDATE", SqlStatement.parse("update t set send = 1, [move] = 2,"
                + " n = case when a = 1 then 0 else 1 end where id = 1",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
        // and they are no statement keywords elsewhere
        assertEquals("INSERT", SqlStatement.parse("insert into q (receive) values (1)",
                SqlStatement.Dialect.POSTGRESQL).writeOperation());
    }

    @Test
    void parseShouldRefuseALexerAmbiguityInAWriteAsInARead() {
        // a backslash before a quote, which NO_BACKSLASH_ESCAPES reads as the end of the text
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "update t set a = 'x\\' where id = 1", SqlStatement.Dialect.MYSQL));
        // a line comment ended by a carriage return alone
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.MYSQL, SqlStatement.Dialect.SQLITE)) {
            assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                    "delete from t where id = 1 -- c\r and 1 = 1", dialect), dialect.name());
        }
        // MySQL starts a comment at -- and a control character: the rest is no clause
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("update t set a = 1 --\u0001 where id = 1",
                        SqlStatement.Dialect.MYSQL).writeOperation());
        assertTrue(e.getMessage().contains("without WHERE"), e.getMessage());
    }

    @Test
    void writeOperationShouldAcceptTheSetOfAnUpdateOnSqlServer() {
        assertEquals("UPDATE", SqlStatement.parse("update orders set status = {{status}}"
                + " where id = {{id}}", SqlStatement.Dialect.SQLSERVER).writeOperation());
        assertEquals("MERGE", SqlStatement.parse("merge into t using s on t.id = s.id"
                + " when matched then update set t.n = s.n"
                + " when not matched then insert (id, n) values (s.id, s.n);",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
        // a T-SQL keyword is no statement keyword elsewhere
        assertEquals("INSERT", SqlStatement.parse("insert into audit (print) values (1)",
                SqlStatement.Dialect.POSTGRESQL).writeOperation());
    }

    @Test
    void writeOperationShouldNotReadABracketIdentifierAsWhere() {
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.SQLSERVER, SqlStatement.Dialect.SQLITE)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("UPDATE orders SET [where] = 1", dialect)
                            .writeOperation(), dialect.name());

            assertTrue(e.getMessage().contains("without WHERE"), e.getMessage());
            assertEquals("DELETE", SqlStatement.parse(
                    "delete from orders where [where] = 1", dialect).writeOperation());
        }
    }

    @Test
    void writeOperationShouldNotReadAWhereHiddenInANestedComment() {
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.POSTGRESQL, SqlStatement.Dialect.SQLSERVER)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse("update orders set status = 'SHIPPED'"
                            + " /* outer /* inner */ where id = 1 */", dialect).writeOperation(),
                    dialect.name());

            assertTrue(e.getMessage().contains("without WHERE"), e.getMessage());
        }
    }

    @Test
    void writeOperationShouldRefuseADeleteAfterAColumnNamedDoOnSqlServer() {
        // DO is no reserved word in T-SQL: the DELETE is a second statement, not an upsert clause
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlStatement.parse("update orders set status = 'SHIPPED' where id = do"
                        + " delete from orders", SqlStatement.Dialect.SQLSERVER)
                        .writeOperation());

        assertTrue(e.getMessage().contains("DELETE"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SqlStatement.parse(
                "insert into t (a) select do from s where id = 1 do delete from t",
                SqlStatement.Dialect.SQLSERVER).writeOperation());
        assertEquals("INSERT", SqlStatement.parse("insert into t (id, n) values ({{id}}, 1)"
                + " on conflict (id) do update set n = t.n + 1",
                SqlStatement.Dialect.POSTGRESQL).writeOperation());
    }

    @Test
    void writeOperationShouldRefuseAMergeThatDeletesEveryRowTheSourceLacks() {
        for (SqlStatement.Dialect dialect
                : List.of(SqlStatement.Dialect.SQLSERVER, SqlStatement.Dialect.POSTGRESQL)) {
            for (String branch : List.of("delete", "update set t.n = 0")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> SqlStatement.parse("merge into t using s on t.id = s.id"
                                + " when matched then update set t.n = s.n"
                                + " when not matched by source then " + branch + ";", dialect)
                                .writeOperation(), dialect.name());

                assertTrue(e.getMessage().contains("BY SOURCE"), e.getMessage());
            }
            assertEquals("MERGE", SqlStatement.parse("merge into t using s on t.id = s.id"
                    + " when not matched by source and t.region = {{region}} then delete"
                    + " when not matched by target then insert (id) values (s.id);", dialect)
                    .writeOperation(), dialect.name());
        }
    }

    @Test
    void writeOperationShouldRefuseASqlServerJoinedDeleteWithoutWhere() {
        for (String sql : List.of(
                "delete t from orders t join stale s on t.id = s.id",
                "delete from orders from orders join stale s on orders.id = s.id",
                "delete t from orders t join (select id from stale where flag = 1) s"
                        + " on t.id = s.id")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlStatement.parse(sql, SqlStatement.Dialect.SQLSERVER)
                            .writeOperation(), sql);

            assertTrue(e.getMessage().contains("without WHERE"), e.getMessage());
        }
        assertEquals("DELETE", SqlStatement.parse("delete t from orders t join stale s"
                + " on t.id = s.id where s.flag = 1", SqlStatement.Dialect.SQLSERVER)
                .writeOperation());
    }

    @Test
    void parseShouldRecordAnAuthoredTerminator() {
        assertTrue(SqlStatement.parse("merge into t using s on t.id = s.id"
                + " when matched then update set t.n = s.n;").terminated());
        assertFalse(SqlStatement.parse("update t set n = 0 where id = 1").terminated());
    }
}
