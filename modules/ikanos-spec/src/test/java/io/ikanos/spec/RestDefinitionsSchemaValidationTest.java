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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * Schema tests for shared {@code definitions} on REST adapters (#1000): sets of REST inputs on
 * resources and operations, schemas in orchestrated outputs, and what the schema rejects.
 */
class RestDefinitionsSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static Schema schema;

    private static final String DEFINITIONS = """
            definitions:
              inputParameterSets:
                ship-key:
                  imo: { in: path, type: string, pattern: "^IMO-[0-9]{7}$", description: "IMO." }
                paging:
                  limit: { in: query, type: integer, description: "Maximum items." }
              schemas:
                crew-member:
                  type: object
                  properties:
                    fullName: { type: string }
                    role: { type: string }
            """;

    private static final String ORCHESTRATED = """
            steps:
              crew: { type: call, call: registry.list-crew }
            mappings:
              - target: crew
                value: "$.crew"
            """;

    @BeforeAll
    static void loadSchema() throws Exception {
        InputStream schemaStream = RestDefinitionsSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
    }

    @Test
    void validateShouldAcceptDefinitionsOnRestAdapterWithEveryUseSite() throws Exception {
        List<Error> errors = schema.validate(rest(DEFINITIONS, """
                ship-crew:
                  path: "/ships/{{imo}}/crew"
                  inputParameterSets: [ship-key]
                  operations:
                    list-crew:
                      method: GET
                      inputParameterSets: [paging]
                %s
                      outputParameters:
                        crew:
                          type: array
                          items: { schema: crew-member }
                        lead: { schema: crew-member }
                        team:
                          type: object
                          properties:
                            captain: { schema: crew-member }
                """.formatted(ORCHESTRATED.indent(6).stripTrailing())));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void validateShouldRejectAnMcpStyleInputInARestSet() throws Exception {
        assertRejected(rest("""
                definitions:
                  inputParameterSets:
                    paging:
                      limit: { type: integer, required: false, description: "Maximum items." }
                """, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [paging]
                      call: registry.list-ships
                """));
    }

    @Test
    void validateShouldRejectASchemaOnARestInput() throws Exception {
        assertRejected(rest(DEFINITIONS, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameters:
                        crew: { schema: crew-member, description: "Crew." }
                      call: registry.list-ships
                """));
    }

    @Test
    void validateShouldRejectASchemaInAMappedRestOutput() throws Exception {
        assertRejected(rest(DEFINITIONS, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      call: registry.list-ships
                      outputParameters:
                        - schema: crew-member
                """));
    }

    @Test
    void validateShouldRejectAQualifiedSetName() throws Exception {
        assertRejected(rest(DEFINITIONS, """
                ships:
                  path: "/ships"
                  operations:
                    list-ships:
                      method: GET
                      inputParameterSets: [api.paging]
                      call: registry.list-ships
                """));
    }

    private static void assertRejected(JsonNode document) {
        List<Error> errors = schema.validate(document);
        assertFalse(errors.isEmpty(), "Expected the document to be rejected");
    }

    private static JsonNode rest(String definitions, String resources) throws Exception {
        return IkanosMappers.yaml().readTree("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: rest
                      port: 3002
                      namespace: api
                %s      resources:
                %s  consumes:
                    - type: http
                      namespace: registry
                      baseUri: "http://localhost:1"
                      resources:
                        crew:
                          path: "/crew"
                          operations:
                            list-crew:
                              method: GET
                            list-ships:
                              method: GET
                """.formatted(IKANOS, definitions.indent(6), resources.indent(8)));
    }
}
