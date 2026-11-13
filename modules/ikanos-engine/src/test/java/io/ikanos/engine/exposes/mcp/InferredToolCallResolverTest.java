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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link InferredToolCallResolver}: an MCP tool without {@code call},
 * {@code steps}, {@code ref} or mock outputs calls the consumed operation named like the tool
 * (#988).
 */
class InferredToolCallResolverTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static final String CONSUMES = """
              consumes:
                - type: "http"
                  namespace: "github"
                  baseUri: "https://api.github.com"
                  resources:
                    issues:
                      path: "/issues"
                      operations:
                        list-issues:
                          method: "GET"
                    repos:
                      path: "/repos"
                      operations:
                        list-repos:
                          method: "GET"
                - type: "http"
                  namespace: "gitlab"
                  baseUri: "https://gitlab.example.com/api"
                  resources:
                    repos:
                      path: "/projects"
                      operations:
                        list-repos:
                          method: "GET"
                - type: "mcp"
                  namespace: "billing"
                  endpoint: "https://billing.example.com/mcp"
                  tools:
                    get-invoice:
                      description: "Retrieve one invoice."
            """;

    @Test
    void resolveShouldSetCallToTheOnlyConsumedOperationWithTheToolName() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "List issues"
                """);

        InferredToolCallResolver.resolve(spec);

        assertEquals("github.list-issues", tool(spec, "list-issues").getCall().getOperation());
    }

    @Test
    void resolveShouldSetCallToAConsumedMcpToolWithTheToolName() {
        IkanosSpec spec = spec(CONSUMES, """
                get-invoice:
                  description: "Get one invoice"
                """);

        InferredToolCallResolver.resolve(spec);

        assertEquals("billing.get-invoice", tool(spec, "get-invoice").getCall().getOperation());
    }

    @Test
    void resolveShouldInferCallWhenToolHasMappedOutputParametersAndWith() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "List issues"
                  with:
                    state: "open"
                  outputParameters:
                    - type: "array"
                      mapping: "$.items"
                """);

        InferredToolCallResolver.resolve(spec);

        McpServerToolSpec tool = tool(spec, "list-issues");
        assertEquals("github.list-issues", tool.getCall().getOperation());
        assertEquals("open", tool.getWith().get("state"));
        assertEquals("$.items", tool.getOutputParameters().get(0).getMapping());
    }

    @Test
    void resolveShouldFailNamingTheToolWhenNoConsumedNamespaceHasTheOperation() {
        IkanosSpec spec = spec(CONSUMES, """
                get-weather:
                  description: "Get the weather"
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InferredToolCallResolver.resolve(spec));

        assertTrue(e.getMessage().contains("MCP tool 'get-weather'"), e.getMessage());
        assertTrue(e.getMessage().contains("no consumed namespace"), e.getMessage());
    }

    @Test
    void resolveShouldFailNamingEveryNamespaceWhenSeveralHaveTheOperation() {
        IkanosSpec spec = spec(CONSUMES, """
                list-repos:
                  description: "List repositories"
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InferredToolCallResolver.resolve(spec));

        assertTrue(e.getMessage().contains("MCP tool 'list-repos'"), e.getMessage());
        assertTrue(e.getMessage().contains("'call: github.list-repos'"), e.getMessage());
        assertTrue(e.getMessage().contains("'call: gitlab.list-repos'"), e.getMessage());
    }

    @Test
    void resolveShouldLeaveAMockToolWithoutCall() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "Mock issues"
                  outputParameters:
                    message:
                      type: "string"
                      value: "none"
                """);

        InferredToolCallResolver.resolve(spec);

        assertNull(tool(spec, "list-issues").getCall());
    }

    @Test
    void resolveShouldLeaveAToolWhoseArrayOutputsAreAllStaticValuesWithoutCall() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "Legacy mock issues"
                  outputParameters:
                    - type: "string"
                      value: "none"
                """);

        InferredToolCallResolver.resolve(spec);

        assertNull(tool(spec, "list-issues").getCall());
    }

    @Test
    void resolveShouldKeepAnExplicitCall() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "List repositories under another name"
                  call: "gitlab.list-repos"
                """);

        InferredToolCallResolver.resolve(spec);

        assertEquals("gitlab.list-repos", tool(spec, "list-issues").getCall().getOperation());
    }

    @Test
    void resolveShouldLeaveAToolWithStepsOrRefWithoutCall() {
        IkanosSpec spec = spec(CONSUMES, """
                list-repos:
                  description: "Orchestrated"
                  steps:
                    fetch:
                      type: "call"
                      call: "github.list-repos"
                get-weather:
                  description: "From a flow"
                  ref: "weather.get-weather"
                """);

        InferredToolCallResolver.resolve(spec);

        assertNull(tool(spec, "list-repos").getCall());
        assertNull(tool(spec, "get-weather").getCall());
    }

    @Test
    void findErrorsShouldReportWithoutChangingTheSpec() {
        IkanosSpec spec = spec(CONSUMES, """
                list-issues:
                  description: "List issues"
                get-weather:
                  description: "Get the weather"
                """);

        List<String> errors = InferredToolCallResolver.findErrors(spec);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("MCP tool 'get-weather'"), errors.get(0));
        assertNull(tool(spec, "list-issues").getCall());
    }

    @Test
    void findErrorsShouldNotReportAMissingOperationWhenConsumesAreImported() {
        IkanosSpec spec = spec("""
                  consumes:
                    - from: "./shared/registry.consumes.yml"
                      import: "registry"
                """, """
                get-ship:
                  description: "Get one ship"
                """);

        assertTrue(InferredToolCallResolver.findErrors(spec).isEmpty());
    }

    private static McpServerToolSpec tool(IkanosSpec spec, String name) {
        McpServerSpec mcp = (McpServerSpec) spec.getCapability().getExposes().get(0);
        return mcp.getTools().get(name);
    }

    private static IkanosSpec spec(String consumes, String tools) {
        String yaml = """
                ikanos: "%s"
                capability:
                %s
                  exposes:
                    - type: "mcp"
                      port: 0
                      namespace: "tools"
                      tools:
                %s
                """.formatted(IKANOS, consumes.stripTrailing(), indent(tools, 8));
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }

    private static String indent(String text, int spaces) {
        String pad = " ".repeat(spaces);
        return text.lines().map(line -> pad + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
