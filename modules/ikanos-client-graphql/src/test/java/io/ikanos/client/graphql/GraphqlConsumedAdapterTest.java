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
package io.ikanos.client.graphql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.restlet.Component;
import org.restlet.data.Protocol;
import org.restlet.ext.graphql.GraphqlServerAdapter;
import tools.jackson.databind.JsonNode;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.graphql.GraphqlClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The GraphQL consumed adapter against a graphql-java server served in-process by Restlet's
 * {@code GraphqlServerAdapter}.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GraphqlConsumedAdapterTest {

    private static Component upstream;
    private static int port;
    private static Path schema;

    @BeforeAll
    static void startUpstream() throws Exception {
        schema = resource("tracker.graphql");
        String sdl = java.nio.file.Files.readString(schema);
        DataFetcher<Object> issue = env -> {
            String id = env.getArgument("id");
            if ("missing".equals(id)) {
                return null;
            }
            Map<String, Object> result = new HashMap<>(Map.of("id", id, "title", "Bug " + id,
                    "state", "open"));
            if ("partial".equals(id)) {
                return DataFetcherResult.newResult().data(result).error(GraphqlErrorBuilder
                        .newError(env).message("priority is hidden").build()).build();
            }
            result.put("priority", 2);
            return result;
        };
        DataFetcher<Object> secret = env -> DataFetcherResult.newResult()
                .error(GraphqlErrorBuilder.newError(env).message("Not allowed")
                        .extensions(Map.of("code", "FORBIDDEN")).build())
                .build();
        DataFetcher<Object> slow = env -> {
            Thread.sleep(3000);
            return "late";
        };
        DataFetcher<Object> headers = env -> env.getGraphQlContext().get("headers");
        DataFetcher<Object> create = env -> Map.of("issue",
                Map.of("id", "new-1", "title", ((Map<?, ?>) env.getArgument("input")).get("title")),
                "received", String.valueOf(env.getArguments()));
        DataFetcher<Object> update = env -> Map.of("issue",
                Map.of("id", env.getArgument("id")), "received",
                String.valueOf(env.getArguments()));
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("issue", issue).dataFetcher("secret", secret)
                        .dataFetcher("slow", slow).dataFetcher("headers", headers))
                .type("Mutation", t -> t.dataFetcher("issueCreate", create)
                        .dataFetcher("issueUpdate", update))
                .build();
        GraphQL graphQL = GraphQL.newGraphQL(new SchemaGenerator()
                .makeExecutableSchema(new SchemaParser().parse(sdl), wiring)).build();

        GraphqlServerAdapter server = new GraphqlServerAdapter();
        server.setGraphQL(graphQL);
        server.setExecutionInputCustomizer((request, input) -> input.graphQLContext(Map.of(
                "headers", "authorization=" + request.getHeaders()
                        .getFirstValue("Authorization", true) + ";tenant="
                        + request.getHeaders().getFirstValue("X-Tenant", true)
                        + ";traceparent=" + (request.getHeaders()
                                .getFirstValue("traceparent", true) != null))));
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, 0);
        upstream.getDefaultHost().attach("/graphql", server);
        upstream.start();
        port = upstream.getServers().get(0).getActualPort();
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        upstream.stop();
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(GraphqlConsumedAdapterTest.class.getClassLoader().getResource(name).toURI());
    }

    private static GraphqlClientSpec spec(String operations, String extra) throws Exception {
        String yaml = """
                type: graphql
                namespace: tracker
                endpoint: "%s"
                schema: "%s"
                %s
                operations:
                %s
                """.formatted("http://localhost:" + port + "/graphql",
                schema.toString().replace('\\', '/'), extra, operations.indent(2).stripTrailing());
        return (GraphqlClientSpec) IkanosMappers.yaml().readValue(yaml, ClientSpec.class);
    }

    private static final String GET_ISSUE = """
            get-issue:
              description: One issue.
              document: |
                query Issue($id: String!) { issue(id: $id) { id title state priority } }
              inputParameters:
                id: { type: string, required: true, description: Issue id. }
            """;

    private static final String SAVE_ISSUE = """
            save-issue:
              description: Create or update.
              select: { when: id, then: update, else: create }
              documents:
                create: |
                  mutation ($input: IssueCreateInput!) {
                    issueCreate(input: $input) { issue { id title } received }
                  }
                update: |
                  mutation ($id: String!, $input: IssueUpdateInput!) {
                    issueUpdate(id: $id, input: $input) { issue { id } received }
                  }
              inputParameters:
                id: { type: string, description: Issue to update. }
                team-id: { type: string, description: Team., wireName: input.teamId }
                title: { type: string, description: Title., wireName: input.title }
                priority: { type: integer, description: Priority., wireName: input.priority }
            """;

    private static GraphqlConsumedAdapter started(GraphqlClientSpec spec) throws Exception {
        GraphqlConsumedAdapter adapter = new GraphqlConsumedAdapter(null, spec, Map.of());
        adapter.start();
        return adapter;
    }

    private static ConsumedResult call(GraphqlConsumedAdapter adapter, String operation,
            Map<String, Object> values) throws Exception {
        return adapter.prepare(operation, new HashMap<>(values)).invoke();
    }

    @Test
    void prepareShouldSendTheDocumentWithTheStepValuesAsVariables() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "42"));

            assertEquals(200, result.status());
            JsonNode issue = result.document().get("issue");
            assertEquals("Bug 42", issue.get("title").asString(), "the document roots at data");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void selectShouldRunTheCreateDocumentWithoutTheSelectorInput() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec(SAVE_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "save-issue",
                    Map.of("title", "New", "team-id", "t1"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            assertEquals("New", result.document().at("/issueCreate/issue/title").asString());
            String received = result.document().at("/issueCreate/received").asString();
            assertTrue(received.contains("teamId=t1"), received);
            assertFalse(received.contains("priority"), "an absent input is left out: " + received);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void selectShouldRunTheUpdateDocumentWhenTheSelectorInputIsPresent() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec(SAVE_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "save-issue",
                    Map.of("id", "7", "title", "Renamed", "priority", "3"));

            assertEquals(200, result.status(), String.valueOf(result.reason()));
            String received = result.document().at("/issueUpdate/received").asString();
            assertTrue(received.contains("priority=3"), "text converted to Int: " + received);
            assertEquals("7", result.document().at("/issueUpdate/issue/id").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void partialDataShouldFailByDefault() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec(GET_ISSUE, ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "partial"));

            assertEquals(502, result.status());
            assertEquals("priority is hidden", result.reason());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void partialDataShouldSucceedWhenAllowed() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec(GET_ISSUE + "  partialData: allow\n",
                ""));
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "partial"));

            assertEquals(200, result.status());
            assertEquals("Bug partial", result.document().at("/issue/title").asString());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void errorsWithoutDataShouldMapTheErrorCode() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec("""
                secret:
                  description: Forbidden.
                  document: "query { secret { id } }"
                """, ""));
        try {
            ConsumedResult result = call(adapter, "secret", Map.of());

            assertEquals(403, result.status());
            assertEquals("Not allowed", result.reason());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void timeoutShouldFailWith504() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec("""
                slow:
                  description: Slow.
                  timeoutMs: 200
                  document: "query { slow }"
                """, ""));
        try {
            ConsumedResult result = call(adapter, "slow", Map.of());

            assertEquals(504, result.status());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void credentialAndStaticHeadersShouldReachTheUpstream() throws Exception {
        GraphqlConsumedAdapter adapter = started(spec("""
                headers:
                  description: Headers seen upstream.
                  document: "query { headers }"
                """, """
                authentication:
                  type: bearer
                  token: secret-token
                inputParameters:
                  X-Tenant: { in: header, value: acme }
                """));
        try {
            ConsumedResult result = call(adapter, "headers", Map.of());

            String seen = result.document().get("headers").asString();
            assertTrue(seen.contains("authorization=Bearer secret-token"), seen);
            assertTrue(seen.contains("tenant=acme"), seen);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void connectionFailureShouldReportNoResponse() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        GraphqlClientSpec spec = spec(GET_ISSUE, "");
        spec.setEndpoint("http://localhost:" + closed + "/graphql");
        GraphqlConsumedAdapter adapter = started(spec);
        try {
            ConsumedResult result = call(adapter, "get-issue", Map.of("id", "1"));

            assertTrue(result.noResponseReceived(), "status " + result.status());
        } finally {
            adapter.stop();
        }
    }

    @Test
    void readOnlyShouldComeFromTheDocuments() throws Exception {
        GraphqlConsumedAdapter adapter = new GraphqlConsumedAdapter(null,
                spec(GET_ISSUE + SAVE_ISSUE, ""), Map.of());

        assertFalse(adapter.isWriteOperation("get-issue"), "a query reads");
        assertTrue(adapter.isWriteOperation("save-issue"), "a mutation writes");
        assertTrue(adapter.declaresOperation("save-issue"));
        assertFalse(adapter.declaresOperation("other"));
    }

    @Test
    void constructorShouldRefuseReadOnlyOnAMutation() throws Exception {
        GraphqlClientSpec spec = spec(SAVE_ISSUE.replace("  select:", "  readOnly: true\n  select:"),
                "");

        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void constructorShouldRefuseATemplateInADocument() throws Exception {
        GraphqlClientSpec spec = spec(GET_ISSUE.replace("issue(id: $id)",
                "issue(id: \"{{id}}\")"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("static"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnInputThatMatchesNoVariable() throws Exception {
        GraphqlClientSpec spec = spec(GET_ISSUE.replace("description: Issue id. }",
                "description: Issue id. }\n    other: { type: string, description: Other. }"),
                "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'other'"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseARequiredVariableThatNoInputFills() throws Exception {
        GraphqlClientSpec spec = spec("""
                get-issue:
                  description: One issue.
                  document: "query ($id: String!) { issue(id: $id) { id } }"
                """, "");

        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void constructorShouldRefuseADocumentTheSchemaRejects() throws Exception {
        GraphqlClientSpec spec = spec(GET_ISSUE.replace("priority }", "assignee }"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("schema"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAWireNameTheSchemaDoesNotDefine() throws Exception {
        GraphqlClientSpec spec = spec(SAVE_ISSUE.replace("input.teamId", "input.team"), "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("input.team"), e.getMessage());
    }

    private static final String FIND_ISSUE = """
            find-issue:
              description: One issue by id or number.
              document: "query Find($by: IssueKey!) { issueFind(by: $by) { id } }"
              inputParameters:
                issue-id: { type: string, description: Issue id., wireName: by.id }
            """;

    @Test
    void wireNameShouldReachAMemberOfAOneOfInput() throws Exception {
        GraphqlClientSpec spec = spec(FIND_ISSUE, "");

        assertDoesNotThrow(() -> new GraphqlConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void constructorShouldRefuseTwoMembersOfAOneOfInput() throws Exception {
        GraphqlClientSpec spec = spec(FIND_ISSUE + """
                    issue-number: { type: integer, description: Issue number., wireName: by.number }
                """, "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
        assertTrue(e.getMessage().contains("'issue-id' and 'issue-number'"), e.getMessage());
        assertTrue(e.getMessage().contains("IssueKey"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseASubscription() throws Exception {
        GraphqlClientSpec spec = spec("""
                watch:
                  description: Watch.
                  document: "subscription { issue }"
                """, "");

        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlConsumedAdapter(null, spec, Map.of()));
    }

    @Test
    void factoryShouldBeFoundThroughServiceLoader() {
        assertTrue(ClientAdapterRegistry.getDefault().registeredTypes().contains("graphql"));
    }

    @Test
    void schemaShouldBeOptional() throws Exception {
        GraphqlClientSpec spec = spec(GET_ISSUE, "");
        spec.setSchema(null);
        GraphqlConsumedAdapter adapter = started(spec);
        try {
            assertNull(adapter.schema());
            assertEquals(200, call(adapter, "get-issue", Map.of("id", "1")).status());
        } finally {
            adapter.stop();
        }
    }
}
