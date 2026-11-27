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
package io.ikanos.spec.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepElicitSpec;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates the shape of a URL mode {@code elicit} step (issue 1151): {@code url}, {@code check}
 * and {@code outputParameters} are required in URL mode and refused in form mode, where
 * {@code fields} stays required.
 */
class ElicitUrlModeSchemaValidationTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static Schema schema;

    private static final String URL_STEP = """
            type: elicit
                        mode: url
                        message: Add a payment method.
                        url: "https://billing.example.com/setup?ref={{elicitation.id}}"
                        check:
                          type: call
                          call: payments.get-setup-status
                          with:
                            ref: "{{elicitation.id}}"
                        outputParameters:
                          paymentMethodId:
                            type: string""";

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = ElicitUrlModeSchemaValidationTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    /** A capability whose only flow has one step, {@code step}, indented for its block. */
    private static String capability(String step) {
        return """
                ikanos: "%s"
                info:
                  display: "Elicit URL mode"
                  description: "elicit url mode test"
                capability:
                  aggregates:
                  - display: billing flows
                    namespace: billing
                    flows:
                      add-payment-method:
                        description: Ask the customer to add a payment method.
                        steps:
                          payment:
                            %s
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9315
                    namespace: billing-mcp
                    description: Billing operations
                    tools:
                      add-payment-method:
                        description: Add a payment method
                        ref: billing.add-payment-method
                """.formatted(VersionHelper.getSchemaVersion(), step);
    }

    private static List<Error> validate(String step) throws Exception {
        return schema.validate(YAML.readTree(capability(step)));
    }

    @Test
    void schemaShouldAcceptAUrlModeStep() throws Exception {
        List<Error> errors = validate(URL_STEP);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "check", "outputParameters"})
    void schemaShouldRejectAUrlModeStepWithout(String property) throws Exception {
        String step = dropBlock(URL_STEP, property);
        assertFalse(validate(step).isEmpty(), "A URL mode step without " + property
                + " must be rejected");
    }

    @Test
    void schemaShouldRejectFieldsOnAUrlModeStep() throws Exception {
        String step = URL_STEP + """

                        fields:
                          apiKey:
                            type: string""";
        assertFalse(validate(step).isEmpty(), "fields must be refused in URL mode");
    }

    @Test
    void schemaShouldRejectAUrlOnAFormStep() throws Exception {
        String step = """
                type: elicit
                            message: Confirm?
                            url: "https://example.com/confirm"
                            fields:
                              confirm:
                                type: boolean""";
        assertFalse(validate(step).isEmpty(), "url must be refused in form mode");
    }

    @Test
    void specShouldReadAUrlModeStep() throws Exception {
        IkanosSpec spec = YAML.readValue(capability(URL_STEP), IkanosSpec.class);
        OperationStepElicitSpec step = (OperationStepElicitSpec) spec.getCapability()
                .getAggregates().get("billing").getFlows().get("add-payment-method").getSteps()
                .get("payment");

        assertTrue(step.isUrlMode());
        assertEquals("payments.get-setup-status", step.getCheck().getCall());
        assertEquals(List.of("paymentMethodId"),
                List.copyOf(step.getOutputParameters().keySet()));
    }

    /** {@code step} without the line of {@code property} and the lines nested under it. */
    private static String dropBlock(String step, String property) {
        StringBuilder kept = new StringBuilder();
        int dropIndent = -1;
        for (String line : step.split("\n")) {
            int indent = line.length() - line.stripLeading().length();
            if (dropIndent >= 0 && indent > dropIndent) {
                continue;
            }
            dropIndent = -1;
            if (line.trim().startsWith(property + ":")) {
                dropIndent = indent;
                continue;
            }
            kept.append(kept.length() > 0 ? "\n" : "").append(line);
        }
        return kept.toString();
    }
}
