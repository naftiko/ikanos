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
package io.ikanos.spec.consumes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.graphql.GraphqlClientSpec;
import io.ikanos.spec.consumes.graphql.GraphqlOperationSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcProcedureSpec;
import io.ikanos.spec.consumes.smithy.SmithyClientSpec;
import io.ikanos.spec.consumes.smithy.SmithyOperationSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Keeps the schema and the runtime model of the contract-described consumed adapters
 * ({@code grpc}, {@code graphql}, {@code smithy}) in step, and checks the golden examples.
 */
class ContractClientSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ContractClientSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String consumes) {
        String call = consumes.contains("type: grpc") ? "orders.get-order"
                : consumes.contains("type: graphql") ? "tracker.get-issue" : "weather.forecast";
        return """
                ikanos: "%s"
                info:
                  title: "Contract clients"
                  description: "Contract client schema test"
                capability:
                  consumes:
                %s
                  exposes:
                    - type: mcp
                      namespace: tools
                      port: 3001
                      tools:
                        ping:
                          description: "Ping"
                          call: "%s"
                """.formatted(IKANOS, consumes.indent(4).stripTrailing(), call);
    }

    private static final String GRPC = """
            - type: grpc
              namespace: orders
              description: Order service.
              endpoint: "http://{{ORDERS_HOST}}:50051"
              descriptorSet: ./contracts/orders.binpb
              service: io.example.orders.v1.OrderService
              authentication:
                type: bearer
                token: "{{TOKEN}}"
              inputParameters:
                x-tenant: { in: header, value: acme }
              procedures:
                get-order:
                  description: One order.
                  rpc: GetOrder
                  timeoutMs: 5000
                  inputParameters:
                    order-id: { type: string, required: true, description: Order id., wireName: id }
                  outputParameters:
                    status: { type: string, value: "$.status" }
            """;

    private static final String GRAPHQL = """
            - type: graphql
              namespace: tracker
              endpoint: "https://api.tracker.example/graphql"
              schema: ./contracts/tracker.graphql
              operations:
                get-issue:
                  description: One issue.
                  document: |
                    query ($id: String!) { issue(id: $id) { title } }
                  partialData: allow
                  inputParameters:
                    id: { type: string, required: true, description: Issue id. }
                  outputParameters:
                    title: { type: string, value: "$.issue.title" }
                save-issue:
                  description: Create or update.
                  readOnly: false
                  select: { when: id, then: update, else: create }
                  documents:
                    create: "mutation ($input: IssueCreateInput!) { issueCreate(input: $input) { ok } }"
                    update: "mutation ($id: String!, $input: IssueUpdateInput!) { issueUpdate(id: $id, input: $input) { ok } }"
                  inputParameters:
                    id: { type: string, description: Issue id. }
                    title: { type: string, description: Title., wireName: input.title }
            """;

    private static final String SMITHY = """
            - type: smithy
              namespace: weather
              baseUri: "https://weather.example.com"
              model:
                - ./contracts/weather.smithy
                - ./contracts/common.smithy
              service: "example.weather#Weather"
              protocol: "aws.protocols#restJson1"
              operations:
                forecast:
                  description: Current forecast.
                  operation: GetCurrentForecast
                  inputParameters:
                    city: { type: string, required: true, description: City id., wireName: cityId }
                  outputParameters:
                    chance: { type: number, value: "$.chanceOfRain" }
            """;

    private static List<Error> validate(String yaml) throws Exception {
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    private static <T extends ClientSpec> T read(String consumes, Class<T> type) throws Exception {
        IkanosSpec spec = YAML.readValue(capability(consumes), IkanosSpec.class);
        return assertInstanceOf(type, spec.getCapability().getConsumes().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grpc", "graphql", "smithy"})
    void schemaShouldAcceptAFullEntry(String type) throws Exception {
        String consumes = switch (type) {
            case "grpc" -> GRPC;
            case "graphql" -> GRAPHQL;
            default -> SMITHY;
        };
        List<Error> errors = validate(capability(consumes));

        assertTrue(errors.isEmpty(), "valid " + type + " entry must validate: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {"capability-grpc-client.yml", "capability-graphql-client.yml",
            "capability-smithy-client.yml"})
    void goldenExamplesShouldValidate(String example) throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("schemas/examples/" + example)) {
            assertNotNull(in, example);
            List<Error> errors = schema.validate(YAML.readTree(in));
            assertTrue(errors.isEmpty(), example + ": " + errors);
        }
    }

    @Test
    void typesShouldBeRegisteredCoreTypes() {
        assertEquals(GrpcClientSpec.class, ClientSpecTypes.resolve("grpc"));
        assertEquals(GraphqlClientSpec.class, ClientSpecTypes.resolve("graphql"));
        assertEquals(SmithyClientSpec.class, ClientSpecTypes.resolve("smithy"));
    }

    @Test
    void runtimeShouldReadEveryFieldOfAGrpcEntry() throws Exception {
        GrpcClientSpec grpc = read(GRPC, GrpcClientSpec.class);

        assertEquals("http://{{ORDERS_HOST}}:50051", grpc.getEndpoint());
        assertEquals("./contracts/orders.binpb", grpc.getDescriptorSet());
        assertEquals("io.example.orders.v1.OrderService", grpc.getService());
        assertEquals("bearer", grpc.getAuthentication().getType());
        InputParameterSpec header = grpc.getInputParameters().get(0);
        assertEquals("x-tenant", header.getName());
        assertEquals("header", header.getIn());
        assertEquals("acme", header.getValue());
        GrpcProcedureSpec procedure = grpc.getProcedures().get("get-order");
        assertEquals("get-order", procedure.getName());
        assertEquals("GetOrder", procedure.getRpc());
        assertEquals(5000, procedure.timeoutMillis());
        assertNull(procedure.getReadOnly(), "derived from the contract when absent");
        InputParameterSpec input = procedure.getInputParameters().get(0);
        assertEquals("id", input.getWireName());
        assertEquals("$.status", procedure.getOutputParameters().get(0).getMapping());
    }

    @Test
    void runtimeShouldReadEveryFieldOfAGraphqlEntry() throws Exception {
        GraphqlClientSpec graphql = read(GRAPHQL, GraphqlClientSpec.class);

        assertEquals("./contracts/tracker.graphql", graphql.getSchema());
        GraphqlOperationSpec get = graphql.getOperations().get("get-issue");
        assertTrue(get.getDocument().startsWith("query"));
        assertTrue(get.allowsPartialData());
        assertEquals(ContractOperationSpec.DEFAULT_TIMEOUT_MS, get.timeoutMillis());
        GraphqlOperationSpec save = graphql.getOperations().get("save-issue");
        assertEquals("id", save.getSelect().getWhen());
        assertEquals("update", save.getSelect().getThen());
        assertEquals("create", save.getSelect().getElse());
        assertEquals(2, save.getDocuments().size());
        assertEquals(Boolean.FALSE, save.getReadOnly());
        assertFalse(save.allowsPartialData());
        assertEquals("input.title", save.getInputParameters().get(1).getWireName());
    }

    @Test
    void runtimeShouldReadEveryFieldOfASmithyEntry() throws Exception {
        SmithyClientSpec smithy = read(SMITHY, SmithyClientSpec.class);

        assertEquals(List.of("./contracts/weather.smithy", "./contracts/common.smithy"),
                smithy.getModel());
        assertEquals("example.weather#Weather", smithy.getService());
        assertEquals("aws.protocols#restJson1", smithy.getProtocol());
        SmithyOperationSpec operation = smithy.getOperations().get("forecast");
        assertEquals("GetCurrentForecast", operation.getOperation());
        assertEquals("cityId", operation.getInputParameters().get(0).getWireName());
    }

    @Test
    void runtimeShouldReadASingleSmithyModelPath() throws Exception {
        SmithyClientSpec smithy = read(SMITHY.replace("""
                  model:
                    - ./contracts/weather.smithy
                    - ./contracts/common.smithy
                """, "  model: ./contracts/weather.smithy\n"), SmithyClientSpec.class);

        assertEquals(List.of("./contracts/weather.smithy"), smithy.getModel());
    }

    @Test
    void contractAdaptersShouldCarryTheirTunnel() throws Exception {
        GraphqlClientSpec graphql = read(GRAPHQL.replace("  schema:", "  tunnel:\n"
                + "    type: ziti\n"
                + "    service: tracker-api\n"
                + "    identity: \"{{ZITI_IDENTITY}}\"\n"
                + "  schema:"), GraphqlClientSpec.class);

        assertInstanceOf(TunneledClientSpec.class, graphql);
        assertEquals("ziti", graphql.getTunnel().getType());
    }

    @Test
    void schemaShouldRefuseDigestAuthenticationOnGrpc() throws Exception {
        String yaml = capability(GRPC.replace("""
                  type: bearer
                  token: "{{TOKEN}}"
                """.indent(2), """
                  type: digest
                  username: u
                  password: "{{PASSWORD}}"
                """.indent(2)));

        assertFalse(validate(yaml).isEmpty(), "digest needs a challenge round trip");
    }

    @Test
    void schemaShouldRefuseAQueryApiKeyOnGrpc() throws Exception {
        String yaml = capability(GRPC.replace("""
                  type: bearer
                  token: "{{TOKEN}}"
                """.indent(2), """
                  type: apikey
                  key: api_key
                  value: "{{KEY}}"
                  placement: query
                """.indent(2)));

        assertFalse(validate(yaml).isEmpty(), "gRPC has no query string");
    }

    @Test
    void schemaShouldRefuseAGrpcEndpointWithAPath() throws Exception {
        String yaml = capability(GRPC.replace("http://{{ORDERS_HOST}}:50051",
                "http://orders:50051/v1"));

        assertFalse(validate(yaml).isEmpty(), "the gRPC path is /package.Service/Method");
    }

    @Test
    void schemaShouldRefuseAHeaderParameterInAnotherLocation() throws Exception {
        String yaml = capability(GRPC.replace("{ in: header, value: acme }",
                "{ in: query, value: acme }"));

        assertFalse(validate(yaml).isEmpty(), "adapter-level parameters are headers only");
    }

    @Test
    void schemaShouldRefuseBothADocumentAndDocuments() throws Exception {
        String yaml = capability(GRAPHQL.replace("      readOnly: false\n",
                "      readOnly: false\n      document: \"query { a }\"\n"));

        assertFalse(validate(yaml).isEmpty(), "document and documents are exclusive");
    }

    @Test
    void schemaShouldRequireSelectWithDocuments() throws Exception {
        String yaml = capability(GRAPHQL.replace(
                "      select: { when: id, then: update, else: create }\n", ""));

        assertFalse(validate(yaml).isEmpty(), "documents need a select rule");
    }

    @Test
    void schemaShouldRefuseAnInputWithoutDescription() throws Exception {
        String yaml = capability(SMITHY.replace("description: City id., ", ""));

        assertFalse(validate(yaml).isEmpty(), "typed inputs are described, as MCP tool inputs");
    }

    @Test
    void schemaShouldRefuseAMalformedWireName() throws Exception {
        String yaml = capability(SMITHY.replace("wireName: cityId", "wireName: \"$.cityId\""));

        assertFalse(validate(yaml).isEmpty(), "wireName is a dotted field path");
    }

    @Test
    void schemaShouldAcceptAMapKeyAndAUnionMemberInAWireName() throws Exception {
        String yaml = capability(SMITHY.replace("wireName: cityId", "wireName: Key.2024.S"));

        assertTrue(validate(yaml).isEmpty(), "a map key may start with a digit: " + validate(yaml));
        assertFalse(validate(capability(SMITHY.replace("wireName: cityId",
                "wireName: 2024.S"))).isEmpty(), "the first segment is a field");
    }

    @Test
    void schemaShouldRefuseAnUnknownOperationField() throws Exception {
        String yaml = capability(SMITHY.replace("operation: GetCurrentForecast",
                "operation: GetCurrentForecast\n      method: GET"));

        assertFalse(validate(yaml).isEmpty(), "operations are closed objects");
    }

    @Test
    void mcpToolInputsShouldStillRefuseAWireName() throws Exception {
        String yaml = capability(GRPC).replace("""
                          description: "Ping"
                """, """
                          description: "Ping"
                          inputParameters:
                            q: { type: string, description: Query., wireName: query }
                """);
        assertTrue(yaml.contains("wireName: query"), "the fixture must carry the tool input");

        assertFalse(validate(yaml).isEmpty(), "wireName is for consumed contract inputs only");
    }
}
