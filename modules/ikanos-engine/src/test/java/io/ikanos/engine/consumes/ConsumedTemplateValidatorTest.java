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
package io.ikanos.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

public class ConsumedTemplateValidatorTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void findErrorsShouldReportUnclosedSectionInBodyWithOperationName() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "json"
                                data: '{"a":1{{#x}},"x":{{x}}}'
                """, "/items"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("svc.create"), errors.get(0));
        assertTrue(errors.get(0).contains("body"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInResourcePath() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("", "/items/{{#id}}"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("path"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInInputParameterValue() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              inputParameters:
                                X-Trace:
                                  in: "header"
                                  value: "trace-{{/id}}"
                """, "/items"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("X-Trace"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInsideStructuredBody() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "json"
                                data:
                                  name: "{{#n}}"
                """, "/items"));

        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInFormField() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "formUrlEncoded"
                                data:
                                  scope: "{{^s}}"
                """, "/items"));

        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldAcceptValidTemplates() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              inputParameters:
                                q:
                                  in: "query"
                                  value: "{{q}}"
                              body:
                                type: "json"
                                data: '{"a":1{{#x}},"x":"{{x}}"{{/x}}}'
                """, "/items/{{id}}"));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void findErrorsShouldNotEchoTemplateTextInMessage() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "text"
                                data: "static-secret-value {{#x}}"
                """, "/items"));

        assertFalse(errors.get(0).contains("static-secret-value"), errors.get(0));
    }

    @Test
    void findErrorsShouldReportErrorKindAndLineWithoutTagName() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "text"
                                data: "{{#s3cr3t}}"
                """, "/items"));

        assertEquals("svc.create body: Section missing close tag (line 1)", errors.get(0));
    }

    @Test
    void findErrorsShouldCheckFieldNamedTypeInsideStructuredBody() throws Exception {
        List<String> errors = ConsumedTemplateValidator.findErrors(spec("""
                              body:
                                type: "json"
                                data:
                                  data:
                                    type: "{{#t}}"
                """, "/items"));

        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInHttpAuthentication() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      authentication:
                        type: "bearer"
                        token: "{{#token}}"
                """.formatted(IKANOS), IkanosSpec.class);

        List<String> errors = ConsumedTemplateValidator.findErrors(spec);

        assertEquals(List.of("svc authentication token: Section missing close tag (line 1)"),
                errors);
    }

    @Test
    void findErrorsShouldReportInvalidTemplateInMcpAuthentication() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "mcp"
                      namespace: "up"
                      endpoint: "https://mcp.example.com/mcp"
                      authentication:
                        type: "apikey"
                        key: "X-Api-Key"
                        value: "{{/key}}"
                        placement: "header"
                """.formatted(IKANOS), IkanosSpec.class);

        List<String> errors = ConsumedTemplateValidator.findErrors(spec);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("up authentication value: "), errors.get(0));
    }

    @Test
    void findErrorsShouldAcceptAuthenticationBoundToVariables() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      authentication:
                        type: "basic"
                        username: "{{USER}}"
                        password: "{{PASSWORD}}"
                """.formatted(IKANOS), IkanosSpec.class);

        assertTrue(ConsumedTemplateValidator.findErrors(spec).isEmpty());
    }

    @Test
    void validateShouldThrowWithAllErrors() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedTemplateValidator.validate(spec("""
                              body:
                                type: "json"
                                data: '{{#a}}'
                """, "/items/{{#id}}")));

        assertTrue(e.getMessage().contains("path") && e.getMessage().contains("body"),
                e.getMessage());
    }

    @Test
    void validateShouldAcceptNullSpec() {
        assertDoesNotThrow(() -> ConsumedTemplateValidator.validate(null));
    }

    private static IkanosSpec spec(String operationYaml, String path) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "%s"
                          operations:
                            create:
                              method: "POST"
                """.formatted(IKANOS, path) + operationYaml;
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }
}
