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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link ConsumedQueryParameterValidator} (#987).
 */
public class ConsumedQueryParameterValidatorTest {

    @Test
    public void findErrorsShouldReportAnOperationNameAlsoInInputParameters() throws Exception {
        List<String> errors = ConsumedQueryParameterValidator.findErrors(spec("", """
                inputParameters:
                  page:
                    in: header
                queryParameters: [page]
                """));

        assertEquals(List.of("api.list declares 'page' in both inputParameters and"
                + " queryParameters"), errors);
    }

    @Test
    public void findErrorsShouldReportAResourceNameListedTwice() throws Exception {
        List<String> errors = ConsumedQueryParameterValidator.findErrors(spec("""
                queryParameters: [page, page]
                """, ""));

        assertEquals(List.of("api resource 'things' lists query parameter 'page' twice in"
                + " queryParameters"), errors);
    }

    @Test
    public void findErrorsShouldAcceptAResourceNameThatAnOperationDeclaresAgain()
            throws Exception {
        List<String> errors = ConsumedQueryParameterValidator.findErrors(spec("""
                queryParameters: [page]
                """, """
                inputParameters:
                  page:
                    in: query
                    value: "1"
                """));

        assertTrue(errors.isEmpty(), "#909: a narrower declaration replaces a broader one: "
                + errors);
    }

    @Test
    public void validateShouldThrowListingTheErrors() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConsumedQueryParameterValidator.validate(spec("", """
                        inputParameters:
                          page:
                            in: query
                        queryParameters: [page]
                        """)));

        assertTrue(error.getMessage().startsWith("Invalid consumed query parameter: "),
                error.getMessage());
    }

    @Test
    public void validateShouldAcceptNullSpec() {
        assertDoesNotThrow(() -> ConsumedQueryParameterValidator.validate(null));
    }

    private static IkanosSpec spec(String resourceFields, String operationFields)
            throws Exception {
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
                %s          operations:
                            list:
                              method: GET
                %s""".formatted(VersionHelper.getSchemaVersion(), resourceFields.indent(10),
                operationFields.indent(14));
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }
}
