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
package io.ikanos.engine.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.engine.consumes.ConsumedQueryParameterValidator;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * An imported consumed adapter goes through {@link SourceFileLoader#deepCopy}. Its
 * {@code queryParameters} lists must survive the copy, so the load-time check still sees a name
 * listed twice or also declared in {@code inputParameters} (#987).
 */
class ImportedQueryParameterListTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ikanos-query-list-import-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // ignore
                }
            });
        }
    }

    @Test
    void importedResourceShouldKeepARepeatedName() throws Exception {
        HttpClientSpec imported = importAdapter("""
                queryParameters: [page, page]
                """, "");

        assertEquals(List.of("api resource 'things' lists query parameter 'page' twice in"
                + " queryParameters"), errors(imported));
    }

    @Test
    void importedResourceShouldKeepANameAlsoInInputParameters() throws Exception {
        HttpClientSpec imported = importAdapter("""
                inputParameters:
                  page:
                    in: header
                queryParameters: [page]
                """, "");

        assertEquals(List.of("api resource 'things' declares 'page' in both inputParameters"
                + " and queryParameters"), errors(imported));
    }

    @Test
    void importedOperationShouldKeepARepeatedName() throws Exception {
        HttpClientSpec imported = importAdapter("", """
                queryParameters: [page, page]
                """);

        assertEquals(List.of("api.list lists query parameter 'page' twice in queryParameters"),
                errors(imported));
    }

    @Test
    void importedOperationShouldKeepANameAlsoInInputParameters() throws Exception {
        HttpClientSpec imported = importAdapter("", """
                inputParameters:
                  page:
                    in: query
                queryParameters: [page]
                """);

        assertEquals(List.of("api.list declares 'page' in both inputParameters and"
                + " queryParameters"), errors(imported));
    }

    @Test
    void importedAdapterShouldReadBackAsDeclared() throws Exception {
        HttpClientSpec imported = importAdapter("""
                inputParameters:
                  tenant:
                    in: header
                queryParameters: [region]
                """, """
                inputParameters:
                  page:
                    in: query
                    value: "1"
                queryParameters: [limit, sort]
                """);

        HttpClientResourceSpec resource = imported.getResources().get("things");
        HttpClientOperationSpec operation = resource.getOperations().get("list");
        assertEquals(List.of("region"), resource.getQueryParameters());
        assertEquals(List.of("tenant", "region"), names(resource.getInputParameters()));
        assertEquals(List.of("limit", "sort"), operation.getQueryParameters());
        assertEquals(List.of("page", "limit", "sort"), names(operation.getInputParameters()));
        assertEquals("query", operation.getInputParameters().get(1).getIn());
        assertEquals(List.of(), errors(imported));
    }

    private HttpClientSpec importAdapter(String resourceFields, String operationFields)
            throws Exception {
        Files.writeString(tempDir.resolve("api.consumes.yml"), """
                ikanos: "%s"
                consumes:
                  - type: http
                    namespace: api
                    baseUri: "https://api.example.com"
                    resources:
                      things:
                        path: "/things"
                %s        operations:
                          list:
                            method: GET
                %s""".formatted(VersionHelper.getSchemaVersion(), resourceFields.indent(8),
                operationFields.indent(12)));
        List<ClientSpec> entries = new ArrayList<>();
        entries.add(new ImportedConsumesHttpSpec("./api.consumes.yml", "api", null));
        new ImportResolver<>(new ConsumesImportStrategy(), new SourceFileLoader())
                .resolveAll(entries, tempDir);
        return assertInstanceOf(HttpClientSpec.class, entries.get(0));
    }

    private static List<String> errors(HttpClientSpec adapter) {
        IkanosSpec spec = new IkanosSpec();
        spec.getConsumes().add(adapter);
        return ConsumedQueryParameterValidator.findErrors(spec);
    }

    private static List<String> names(List<InputParameterSpec> params) {
        return params.stream().map(InputParameterSpec::getName).toList();
    }
}
