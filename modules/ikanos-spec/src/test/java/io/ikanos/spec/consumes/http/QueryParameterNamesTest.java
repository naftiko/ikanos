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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for the {@code queryParameters} list of consumed HTTP resources and operations
 * (#987), read the way the engine reads a capability.
 */
public class QueryParameterNamesTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    public void getInputParametersShouldExpandEachListedNameIntoAQueryParameter() throws Exception {
        HttpClientOperationSpec operation = operation("""
                queryParameters: [state, page]
                """);

        List<InputParameterSpec> parameters = operation.getInputParameters();

        assertEquals(List.of("state", "page"),
                parameters.stream().map(InputParameterSpec::getName).toList());
        assertTrue(parameters.stream().allMatch(p -> "query".equals(p.getIn())));
    }

    @Test
    public void getInputParametersShouldMatchTheLongFormEntryForAListedName() throws Exception {
        InputParameterSpec listed = operation("""
                queryParameters: [page]
                """).getInputParameters().get(0);
        InputParameterSpec longForm = operation("""
                inputParameters:
                  page:
                    in: query
                """).getInputParameters().get(0);

        assertEquals(longForm.getName(), listed.getName());
        assertEquals(longForm.getIn(), listed.getIn());
        assertEquals(longForm.getType(), listed.getType());
        assertEquals(longForm.isRequired(), listed.isRequired());
        assertNull(listed.getValue());
        assertNull(listed.getWireName());
    }

    @Test
    public void getInputParametersShouldPutListedNamesAfterDeclaredEntriesWhicheverComesFirst()
            throws Exception {
        String listFirst = """
                queryParameters: [page]
                inputParameters:
                  X-Mode:
                    in: header
                    value: "a"
                """;
        String entriesFirst = """
                inputParameters:
                  X-Mode:
                    in: header
                    value: "a"
                queryParameters: [page]
                """;

        assertEquals(List.of("X-Mode", "page"), names(operation(listFirst)));
        assertEquals(List.of("X-Mode", "page"), names(operation(entriesFirst)));
    }

    @Test
    public void getInputParametersShouldExpandResourceLevelNames() throws Exception {
        HttpClientResourceSpec resource = resource("""
                queryParameters: [per_page]
                """);

        assertEquals(List.of("per_page"), resource.getInputParameters().stream()
                .map(InputParameterSpec::getName).toList());
        assertEquals("query", resource.getInputParameters().get(0).getIn());
    }

    @Test
    public void queryParameterErrorsShouldReportANameAlsoDeclaredInInputParameters()
            throws Exception {
        HttpClientOperationSpec operation = operation("""
                inputParameters:
                  page:
                    in: query
                    value: "1"
                queryParameters: [page]
                """);

        assertEquals(List.of("api.list declares 'page' in both inputParameters and"
                + " queryParameters"), operation.queryParameterErrors("api.list"));
        assertEquals("1", operation.getInputParameters().get(0).getValue(),
                "the declared entry should be kept");
    }

    @Test
    public void queryParameterErrorsShouldReportANameListedTwice() throws Exception {
        HttpClientResourceSpec resource = resource("""
                queryParameters: [page, page]
                """);

        assertEquals(List.of("api resource 'things' lists query parameter 'page' twice in"
                + " queryParameters"), resource.queryParameterErrors("api resource 'things'"));
        assertEquals(1, resource.getInputParameters().size());
    }

    @Test
    public void queryParameterErrorsShouldBeEmptyForDistinctNames() throws Exception {
        assertTrue(operation("""
                inputParameters:
                  X-Mode:
                    in: header
                queryParameters: [page, state]
                """).queryParameterErrors("api.list").isEmpty());
    }

    @Test
    public void serializationShouldWriteListedNamesAsInputParameters() throws Exception {
        String json = IkanosMappers.json().writeValueAsString(operation("""
                queryParameters: [page]
                """));

        assertFalse(json.contains("queryParameters"), json);
        assertTrue(json.contains("\"page\""), json);
    }

    private static List<String> names(HttpClientOperationSpec operation) {
        return operation.getInputParameters().stream().map(InputParameterSpec::getName).toList();
    }

    private static HttpClientOperationSpec operation(String fields) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://api.example.com"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: GET
                %s""".formatted(IKANOS, fields.indent(14));
        return httpClient(yaml).getResources().get("things").getOperations().get("list");
    }

    private static HttpClientResourceSpec resource(String fields) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://api.example.com"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: GET
                %s""".formatted(IKANOS, fields.indent(10));
        return httpClient(yaml).getResources().get("things");
    }

    private static HttpClientSpec httpClient(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }
}
