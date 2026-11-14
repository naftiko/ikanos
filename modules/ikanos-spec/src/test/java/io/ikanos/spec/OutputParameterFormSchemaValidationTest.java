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

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The output form of an executable unit follows its mode (#1035): a list of mapped outputs in
 * call mode, a map of orchestrated outputs in steps mode, and a map of mock outputs when the unit
 * has no call, no steps and no ref. The schema checks each form against its mode on REST
 * operations, MCP tools and aggregate flows; a consumed HTTP operation only takes a map of
 * consumed outputs.
 */
class OutputParameterFormSchemaValidationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String CALL = """
            call: "api.list-things"
            """;

    private static final String STEPS = """
            steps:
              list:
                type: "call"
                call: "api.list-things"
            mappings:
              - target: "total"
                value: "$.list.total"
            """;

    private static final String MAP_WITH_BOGUS_MEMBER = """
            outputParameters:
              total:
                bogus: 1
            """;

    private static final String MAPPED_LIST = """
            outputParameters:
              - type: "number"
                mapping: "$.total"
            """;

    private static final String ORCHESTRATED_MAP = """
            outputParameters:
              total:
                type: "number"
            """;

    private static final String MOCK_MAP = """
            outputParameters:
              total:
                type: "string"
                value: "42"
            """;

    private static final String CONSUMED =
            "$.capability.consumes[0].resources.things.operations.list-things";
    private static final String TOOL = "$.capability.exposes[0].tools.list-things";
    private static final String OPERATION =
            "$.capability.exposes[1].resources.things.operations.list";
    private static final String FLOW = "$.capability.aggregates[0].flows.list";

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = OutputParameterFormSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(IkanosMappers.json().readTree(in));
        }
    }

    // REST operations

    @Test
    void validateShouldRejectMapOutputsOnRestOperationInCallMode() throws Exception {
        assertRejected(OPERATION, restOperation(CALL + """
                outputParameters:
                  total:
                    type: "string"
                """), "#1035: a map of outputs belongs to steps mode, not to call mode");
    }

    @Test
    void validateShouldRejectMockOutputsOnRestOperationInCallMode() throws Exception {
        assertRejected(OPERATION, restOperation(CALL + MOCK_MAP),
                "#1035: mock outputs belong to a unit with no call and no steps");
    }

    @Test
    void validateShouldRejectBogusMappedOutputOnRestOperationInCallMode() throws Exception {
        assertRejected(OPERATION, restOperation(CALL + """
                outputParameters:
                  - bogus: 1
                """), "A mapped output must match MappedOutputParameter");
    }

    @Test
    void validateShouldRejectBogusOrchestratedOutputOnRestOperationInStepsMode()
            throws Exception {
        assertRejected(OPERATION, restOperation(STEPS + MAP_WITH_BOGUS_MEMBER),
                "#1035: a steps-mode output must match OrchestratedOutputParameter");
    }

    @Test
    void validateShouldRejectMockOutputsOnRestOperationInStepsMode() throws Exception {
        assertRejected(OPERATION, restOperation(STEPS + MOCK_MAP),
                "#1035: an orchestrated output takes no value, and mock mode has no steps");
    }

    @Test
    void validateShouldRejectMappedOutputsOnRestOperationInStepsMode() throws Exception {
        assertRejected(OPERATION, restOperation(STEPS + MAPPED_LIST),
                "#1035: a list of mapped outputs belongs to call mode, not to steps mode");
    }

    @Test
    void validateShouldRejectBogusMockOutputOnRestOperation() throws Exception {
        assertRejected(OPERATION, restOperation(MAP_WITH_BOGUS_MEMBER),
                "#1035: a mock output must match MockOutputParameter");
    }

    @Test
    void validateShouldAcceptMappedOutputsOnRestOperationInCallMode() throws Exception {
        assertAccepted(restOperation(CALL + MAPPED_LIST));
    }

    @Test
    void validateShouldAcceptOrchestratedOutputsOnRestOperationInStepsMode() throws Exception {
        assertAccepted(restOperation(STEPS + ORCHESTRATED_MAP));
    }

    @Test
    void validateShouldAcceptMockMapOutputsOnRestOperation() throws Exception {
        assertAccepted(restOperation(MOCK_MAP));
    }

    @Test
    void validateShouldAcceptMockListOutputsOnRestOperation() throws Exception {
        assertAccepted(restOperation("""
                outputParameters:
                  - type: "string"
                    value: "42"
                """));
    }

    // MCP tools

    @Test
    void validateShouldRejectMockOutputsOnMcpToolInCallMode() throws Exception {
        assertRejected(TOOL, mcpTool(CALL + MOCK_MAP),
                "#1035: mock outputs belong to a tool with no call and no steps");
    }

    @Test
    void validateShouldRejectMockOutputsOnMcpToolInStepsMode() throws Exception {
        assertRejected(TOOL, mcpTool(STEPS + MOCK_MAP),
                "#1035: an orchestrated output takes no value, and mock mode has no steps");
    }

    @Test
    void validateShouldAcceptMappedOutputsOnMcpToolInCallMode() throws Exception {
        assertAccepted(mcpTool(CALL + MAPPED_LIST));
    }

    @Test
    void validateShouldAcceptOrchestratedOutputsOnMcpToolInStepsMode() throws Exception {
        assertAccepted(mcpTool(STEPS + ORCHESTRATED_MAP));
    }

    @Test
    void validateShouldAcceptMockOutputsOnMcpTool() throws Exception {
        assertAccepted(mcpTool(MOCK_MAP));
    }

    // Aggregate flows

    @Test
    void validateShouldRejectMockOutputsOnAggregateFlowInCallMode() throws Exception {
        assertRejected(FLOW, aggregateFlow(CALL + MOCK_MAP),
                "#1035: mock outputs belong to a flow with no call and no steps");
    }

    @Test
    void validateShouldRejectMockOutputsOnAggregateFlowInStepsMode() throws Exception {
        assertRejected(FLOW, aggregateFlow(STEPS + MOCK_MAP),
                "#1035: an orchestrated output takes no value, and mock mode has no steps");
    }

    @Test
    void validateShouldAcceptMappedOutputsOnAggregateFlowInCallMode() throws Exception {
        assertAccepted(aggregateFlow(CALL + MAPPED_LIST));
    }

    @Test
    void validateShouldAcceptOrchestratedOutputsOnAggregateFlowInStepsMode() throws Exception {
        assertAccepted(aggregateFlow(STEPS + ORCHESTRATED_MAP));
    }

    @Test
    void validateShouldAcceptMockOutputsOnAggregateFlow() throws Exception {
        assertAccepted(aggregateFlow(MOCK_MAP));
    }

    // Consumed HTTP operations

    @Test
    void validateShouldRejectBogusConsumedOutputOnConsumedHttpOperation() throws Exception {
        assertRejected(CONSUMED, document(MAP_WITH_BOGUS_MEMBER, "", ""),
                "A consumed output must match ConsumedOutputParameter");
    }

    @Test
    void validateShouldRejectListOutputsOnConsumedHttpOperation() throws Exception {
        assertRejected(CONSUMED, document(MAPPED_LIST, "", ""), "A consumed HTTP operation takes a map of consumed outputs only");
    }

    @Test
    void validateShouldAcceptConsumedOutputsOnConsumedHttpOperation() throws Exception {
        assertAccepted(document("""
                outputParameters:
                  total:
                    type: "number"
                    value: "$.total"
                """, "", ""));
    }

    private static JsonNode restOperation(String fields) throws Exception {
        return document("", "", fields);
    }

    private static JsonNode mcpTool(String fields) throws Exception {
        return document("", fields, "");
    }

    private static JsonNode aggregateFlow(String fields) throws Exception {
        return IkanosMappers.yaml().readTree("""
                ikanos: "%s"
                capability:
                  aggregates:
                    - namespace: "things"
                      display: "Things"
                      flows:
                        list:
                          description: "List things."
                %s  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.test"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list-things:
                              method: "GET"
                """.formatted(IKANOS, fields.indent(10)));
    }

    /**
     * A capability with one consumed HTTP operation, one MCP tool and one REST operation, each
     * completed with the given fields. The tool and the operation call the consumed operation
     * when they declare no fields of their own.
     */
    private static JsonNode document(String consumedFields, String toolFields,
            String operationFields) throws Exception {
        String tool = toolFields.isBlank() ? CALL : toolFields;
        String operation = operationFields.isBlank() ? CALL : operationFields;
        return IkanosMappers.yaml().readTree("""
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "api"
                      baseUri: "https://api.example.test"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list-things:
                              method: "GET"
                %s  exposes:
                    - type: "mcp"
                      namespace: "things-mcp"
                      port: 3001
                      tools:
                        list-things:
                          description: "List things."
                %s    - type: "rest"
                      namespace: "things-rest"
                      port: 3002
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: "GET"
                %s""".formatted(IKANOS, consumedFields.indent(14), tool.indent(10),
                operation.indent(14)));
    }

    /** Asserts that the document is rejected for the unit at the given location. */
    private static void assertRejected(String location, JsonNode document, String message) {
        List<Error> errors = schema.validate(document);
        assertTrue(errors.stream()
                .anyMatch(e -> String.valueOf(e.getInstanceLocation()).startsWith(location)),
                message + "; got: " + errors);
    }

    private static void assertAccepted(JsonNode document) {
        List<Error> errors = schema.validate(document);
        assertTrue(errors.isEmpty(), "Expected the document to be valid, got:\n" + errors);
    }
}
