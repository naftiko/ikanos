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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ClientSpecTypes;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Keeps the schema and the runtime model of {@code consumes: type: sql} in step: a valid entry
 * passes the schema and deserializes to {@link SqlClientSpec} with every field.
 */
class SqlClientSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = SqlClientSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String procedure) {
        return """
                ikanos: "%s"
                info:
                  display: "SQL client"
                  description: "SQL client schema test"
                capability:
                  consumes:
                    - type: sql
                      namespace: erp
                      description: ERP customer master data.
                      connection:
                        url: "{{JDBC_URL}}"
                        username: "{{DB_USER}}"
                        password: "{{DB_PASSWORD}}"
                        properties:
                          ssl: "true"
                      pool:
                        maxSize: 5
                        connectionTimeoutMs: 2000
                      procedures:
                %s
                  exposes:
                    - type: mcp
                      namespace: erp-tools
                      port: 3001
                      tools:
                        get-customer:
                          description: "One customer"
                          call: "erp.get-customer"
                """.formatted(IKANOS, procedure.indent(8).stripTrailing());
    }

    private static final String GET_CUSTOMER = """
            get-customer:
              description: One customer by id.
              statement: |
                select c.id, c.name from public.customers c where c.id = :customerId
              timeoutMs: 5000
              fetchSize: 100
              inputParameters:
                customer-id:
                  type: string
                  description: ERP customer id.
                  placeholder: customerId
                  sqlType: VARCHAR
              outputParameters:
                name: { type: string, value: "$.rows[0].name" }
            """;

    private static List<Error> validate(String yaml) throws Exception {
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    @Test
    void schemaShouldAcceptAFullSqlEntry() throws Exception {
        List<Error> errors = validate(capability(GET_CUSTOMER));

        assertTrue(errors.isEmpty(), "valid sql entry must validate: " + errors);
    }

    @Test
    void runtimeShouldReadEveryFieldOfASqlEntry() throws Exception {
        IkanosSpec spec = YAML.readValue(capability(GET_CUSTOMER), IkanosSpec.class);

        SqlClientSpec sql = assertInstanceOf(SqlClientSpec.class,
                spec.getCapability().getConsumes().get(0));
        assertEquals("{{JDBC_URL}}", sql.getConnection().getUrl());
        assertEquals("true", sql.getConnection().getProperties().get("ssl"));
        assertEquals(5, sql.getPool().getMaxSize());
        SqlProcedureSpec procedure = sql.getProcedures().get("get-customer");
        assertEquals("get-customer", procedure.getName());
        assertEquals(5000, procedure.getTimeoutMs());
        InputParameterSpec input = procedure.getInputParameters().get(0);
        assertEquals("customerId", input.getPlaceholder());
        assertEquals("VARCHAR", input.getSqlType());
        assertEquals("$.rows[0].name", procedure.getOutputParameters().get(0).getMapping());
    }

    @Test
    void sqlShouldBeARegisteredCoreType() {
        assertEquals(SqlClientSpec.class, ClientSpecTypes.resolve("sql"));
    }

    @Test
    void schemaShouldRequireAStatement() throws Exception {
        String yaml = capability("""
                get-customer:
                  description: No statement.
                """);

        assertFalse(validate(yaml).isEmpty(), "a procedure without a statement is invalid");
    }

    @Test
    void schemaShouldRejectAUrlThatIsNotJdbc() throws Exception {
        String yaml = capability(GET_CUSTOMER).replace("\"{{JDBC_URL}}\"",
                "\"https://db.example.com\"");

        assertFalse(validate(yaml).isEmpty(), "connection.url must be a JDBC URL");
    }

    @Test
    void schemaShouldRejectAnUnknownSqlType() throws Exception {
        String yaml = capability(GET_CUSTOMER).replace("sqlType: VARCHAR", "sqlType: TEXTISH");

        assertFalse(validate(yaml).isEmpty(), "sqlType must be a JDBCType name");
    }

    @Test
    void referenceExampleShouldValidateAgainstSchema() throws Exception {
        String resource = "schemas/examples/capability-sql-client.yml";
        try (InputStream in = SqlClientSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the classpath");
            String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            List<Error> errors = validate(yaml);
            assertTrue(errors.isEmpty(), "reference example must validate: " + errors);
            assertInstanceOf(SqlClientSpec.class, YAML.readValue(yaml, IkanosSpec.class)
                    .getCapability().getConsumes().get(0));
        }
    }

    @Test
    void schemaShouldRejectAPlaceholderThatIsNotASqlIdentifier() throws Exception {
        String yaml = capability(GET_CUSTOMER).replace("placeholder: customerId",
                "placeholder: customer-id");

        assertFalse(validate(yaml).isEmpty(), "placeholder must be a SQL identifier");
    }
}
