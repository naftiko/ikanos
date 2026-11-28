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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.node.NullNode;

/**
 * Unit tests for {@link InputDefaults}: how an input default fills an argument (#979), and the
 * load-time checks of a default against its parameter's {@code type} and {@code enum}.
 */
class InputDefaultsTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static InputParameterSpec param(String name, String type, boolean required,
            String defaultJson) throws Exception {
        InputParameterSpec param = new InputParameterSpec();
        param.setName(name);
        param.setType(type);
        param.setRequired(required);
        if (defaultJson != null) {
            param.setDefaultValue(IkanosMappers.json().readTree(defaultJson));
        }
        return param;
    }

    // ── apply ──

    @Test
    void applyShouldAddDefaultWhenArgumentIsAbsent() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("period", "string", false, "\"LAST_90DAYS\"")), Map.of());

        assertEquals("LAST_90DAYS", applied.get("period"));
    }

    @Test
    void applyShouldReplaceNullArgumentWithDefault() throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("period", null);

        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("period", "string", false, "\"LAST_90DAYS\"")), arguments);

        assertEquals("LAST_90DAYS", applied.get("period"));
    }

    /**
     * A REST request whose JSON body is {@code null}, bound to an untyped {@code in: body} input,
     * hands the flow a {@code NullNode}: it is left out like a Java null, so the default applies.
     */
    @Test
    void applyShouldReplaceJsonNullNodeWithDefault() throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("period", NullNode.getInstance());

        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("period", "string", false, "\"LAST_90DAYS\"")), arguments);

        assertEquals("LAST_90DAYS", applied.get("period"));
    }

    @Test
    void applyShouldAddDefaultWhenArgumentsAreNull() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("size", "integer", false, "200")), null);

        assertEquals(200, ((Number) applied.get("size")).intValue());
    }

    @Test
    void applyShouldKeepCallerValueWhenArgumentIsPresent() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("period", "string", false, "\"LAST_90DAYS\"")),
                Map.of("period", "LAST_7DAYS"));

        assertEquals("LAST_7DAYS", applied.get("period"));
    }

    @Test
    void applyShouldIgnoreDefaultOfRequiredParameter() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("id", "string", true, "\"x\"")), Map.of());

        assertFalse(applied.containsKey("id"), "a default on a required input never applies");
    }

    @Test
    void applyShouldReturnSameArgumentsWhenNoDefaultApplies() throws Exception {
        Map<String, Object> arguments = Map.of("period", "LAST_7DAYS");

        assertSame(arguments, InputDefaults.apply(
                List.of(param("period", "string", false, "\"LAST_90DAYS\"")), arguments));
    }

    @Test
    void applyShouldConvertObjectDefaultToMap() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("filter", "object", false, "{\"status\":\"open\"}")), Map.of());

        assertEquals(Map.of("status", "open"), applied.get("filter"));
    }

    @Test
    void applyShouldConvertArrayDefaultToList() throws Exception {
        Map<String, Object> applied = InputDefaults.apply(
                List.of(param("tags", "array", false, "[\"a\",\"b\"]")), Map.of());

        assertEquals(List.of("a", "b"), applied.get("tags"));
    }

    @Test
    void applyShouldGiveEachCallItsOwnCopyOfAContainerDefault() throws Exception {
        List<InputParameterSpec> params = List.of(param("tags", "array", false, "[\"a\"]"));

        @SuppressWarnings("unchecked")
        List<Object> first = (List<Object>) InputDefaults.apply(params, Map.of()).get("tags");
        first.add("mutated");

        assertEquals(List.of("a"), InputDefaults.apply(params, Map.of()).get("tags"));
    }

    // ── defaultError ──

    @Test
    void defaultErrorShouldAcceptDefaultMatchingEachType() throws Exception {
        assertNull(InputDefaults.defaultError(param("a", "string", false, "\"x\"")));
        assertNull(InputDefaults.defaultError(param("b", "integer", false, "200")));
        assertNull(InputDefaults.defaultError(param("c", "integer", false, "2.0")));
        assertNull(InputDefaults.defaultError(param("d", "number", false, "0.5")));
        assertNull(InputDefaults.defaultError(param("e", "boolean", false, "false")));
        assertNull(InputDefaults.defaultError(param("f", "array", false, "[1]")));
        assertNull(InputDefaults.defaultError(param("g", "object", false, "{}")));
    }

    @Test
    void defaultErrorShouldRejectStringDefaultOnIntegerParameter() throws Exception {
        assertEquals("default does not match type 'integer'",
                InputDefaults.defaultError(param("size", "integer", false, "\"200\"")));
    }

    @Test
    void defaultErrorShouldRejectFractionalDefaultOnIntegerParameter() throws Exception {
        assertEquals("default does not match type 'integer'",
                InputDefaults.defaultError(param("size", "integer", false, "2.5")));
    }

    @Test
    void defaultErrorShouldRejectNumberDefaultOnStringParameter() throws Exception {
        assertEquals("default does not match type 'string'",
                InputDefaults.defaultError(param("period", "string", false, "90")));
    }

    @Test
    void defaultErrorShouldRejectDefaultOutsideEnum() throws Exception {
        InputParameterSpec period = param("period", "string", false, "\"LAST_YEAR\"");
        period.getEnumeration().addAll(List.of("LAST_7DAYS", "LAST_90DAYS"));

        assertEquals("default is not one of the enum values",
                InputDefaults.defaultError(period));
    }

    @Test
    void defaultErrorShouldAcceptNumberDefaultEqualToEnumValue() throws Exception {
        InputParameterSpec size = param("size", "number", false, "50");
        size.getEnumeration().addAll(List.of("25", "50.0"));

        assertNull(InputDefaults.defaultError(size));
    }

    @Test
    void defaultErrorShouldAcceptBooleanDefaultInEnum() throws Exception {
        InputParameterSpec draft = param("draft", "boolean", false, "true");
        draft.getEnumeration().add("true");

        assertNull(InputDefaults.defaultError(draft));
    }

    /**
     * An integer enum value written {@code "50.0"} or {@code "1e2"} is advertised as a string, so
     * the check reads it the same way and a number default does not match it.
     */
    @Test
    void defaultErrorShouldRejectNumberDefaultAgainstEnumValueAdvertisedAsString()
            throws Exception {
        InputParameterSpec decimal = param("size", "integer", false, "50");
        decimal.getEnumeration().add("50.0");
        InputParameterSpec exponent = param("size", "integer", false, "100");
        exponent.getEnumeration().add("1e2");

        assertEquals("default is not one of the enum values", InputDefaults.defaultError(decimal));
        assertEquals("default is not one of the enum values",
                InputDefaults.defaultError(exponent));
    }

    @Test
    void defaultErrorShouldAcceptIntegerDefaultInIntegerEnum() throws Exception {
        InputParameterSpec size = param("size", "integer", false, "50");
        size.getEnumeration().addAll(List.of("25", "50"));

        assertNull(InputDefaults.defaultError(size));
    }

    // ── exampleError ──

    @Test
    void exampleErrorShouldAcceptExampleMatchingEachType() {
        assertNull(InputDefaults.exampleError("sev2", "string", List.of()));
        assertNull(InputDefaults.exampleError("90", "string", List.of()));
        assertNull(InputDefaults.exampleError("90", null, List.of()));
        assertNull(InputDefaults.exampleError("25", "integer", List.of()));
        assertNull(InputDefaults.exampleError("0.5", "number", List.of()));
        assertNull(InputDefaults.exampleError("true", "boolean", List.of()));
    }

    /** Numbers read as for a default: 25.0 is an integer, as {@code default: 25.0} is. */
    @Test
    void exampleErrorShouldReadNumbersAsDefaultErrorDoes() throws Exception {
        assertNull(InputDefaults.exampleError("25.0", "integer", List.of()));
        assertNull(InputDefaults.defaultError(param("size", "integer", false, "25.0")));

        assertEquals("example '2.5' does not match type 'integer'",
                InputDefaults.exampleError("2.5", "integer", List.of()));
        assertEquals("default does not match type 'integer'",
                InputDefaults.defaultError(param("size", "integer", false, "2.5")));
    }

    @Test
    void exampleErrorShouldRejectExampleThatDoesNotParseAsTheType() {
        assertEquals("example 'ten' does not match type 'number'",
                InputDefaults.exampleError("ten", "number", List.of()));
        assertEquals("example 'yes' does not match type 'boolean'",
                InputDefaults.exampleError("yes", "boolean", List.of()));
        assertEquals("example 'x' does not match type 'object'",
                InputDefaults.exampleError("x", "object", List.of()));
    }

    @Test
    void exampleErrorShouldRejectExampleOutsideEnum() {
        assertEquals("example 'sev9' is not one of the enum values",
                InputDefaults.exampleError("sev9", "string", List.of("sev1", "sev2")));
        assertNull(InputDefaults.exampleError("sev2", "string", List.of("sev1", "sev2")));
    }

    /** Enum values are compared as advertised, as for a default: numerically, by type. */
    @Test
    void exampleErrorShouldCompareEnumValuesAsAdvertised() {
        assertNull(InputDefaults.exampleError("1", "number", List.of("1.0")));
        assertNull(InputDefaults.exampleError("50.0", "integer", List.of("25", "50")));
        assertEquals("example '50' is not one of the enum values",
                InputDefaults.exampleError("50", "integer", List.of("50.0")));
    }

    @Test
    void typedExampleValueShouldReadAnIntegralDecimalAsAnInteger() {
        assertEquals(new java.math.BigInteger("25"), InputDefaults.typedExampleValue("integer",
                "25.0"));
        assertEquals("2.5", InputDefaults.typedExampleValue("integer", "2.5"));
        assertEquals(new java.math.BigDecimal("2.5"), InputDefaults.typedExampleValue("number",
                "2.5"));
        assertEquals("25.0", InputDefaults.typedExampleValue("string", "25.0"));
        assertEquals("1e999999999", InputDefaults.typedExampleValue("integer", "1e999999999"),
                "Never expanded to a billion digits");
        assertEquals("example '1e999999999' does not match type 'integer'",
                InputDefaults.exampleError("1e999999999", "integer", List.of()));
    }

    @Test
    void typedEnumValueShouldUseTheParameterType() {
        assertEquals(new java.math.BigInteger("50"), InputDefaults.typedEnumValue("integer", "50"));
        assertEquals("50.0", InputDefaults.typedEnumValue("integer", "50.0"));
        assertEquals("1e2", InputDefaults.typedEnumValue("integer", "1e2"));
        assertEquals(new java.math.BigDecimal("1e2"), InputDefaults.typedEnumValue("number", "1e2"));
        assertEquals(Boolean.TRUE, InputDefaults.typedEnumValue("boolean", "true"));
        assertEquals("yes", InputDefaults.typedEnumValue("boolean", "yes"));
        assertEquals("50", InputDefaults.typedEnumValue("string", "50"));
        assertEquals("50", InputDefaults.typedEnumValue(null, "50"));
    }

    // ── findErrors / findWarnings / validate ──

    private static IkanosSpec spec(String toolParameters) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  aggregates:
                    - display: "CRM"
                      namespace: "crm-domain"
                      flows:
                        ask:
                          description: "Ask."
                          inputParameters:
                            size: { type: integer, description: Size, required: false, default: "50" }
                  consumes:
                    - type: mcp
                      namespace: upstream
                      endpoint: https://upstream.example.com/mcp
                      discovery: off
                      tools:
                        search:
                          description: Search
                          inputParameters:
                            flag: { type: boolean, description: Flag, required: false, default: 1 }
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: crm
                      tools:
                        ask-account:
                          description: "Ask."
                          inputParameters:
                %s
                """.formatted(IKANOS, toolParameters.indent(12).stripTrailing());
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }

    @Test
    void findErrorsShouldNameEveryMismatchedDefaultWithItsLocation() throws Exception {
        List<String> errors = InputDefaults.findErrors(spec("""
                period: { type: string, description: Period, required: false, default: 7 }
                """));

        assertEquals(List.of(
                "MCP tool 'crm.ask-account' input parameter 'period': default does not match"
                        + " type 'string'",
                "aggregate flow 'crm-domain.ask' input parameter 'size': default does not match"
                        + " type 'integer'",
                "consumed MCP tool 'upstream.search' input parameter 'flag': default does not"
                        + " match type 'boolean'"),
                errors);
    }

    @Test
    void findExampleErrorsShouldNameEveryMismatchedExampleWithItsLocation() throws Exception {
        List<String> errors = InputDefaults.findExampleErrors(spec("""
                size: { type: integer, description: Size, examples: [25.0, 2.5] }
                severity: { type: string, description: S, enum: [sev1, sev2], examples: [sev9] }
                labels:
                  type: object
                  description: Labels
                  properties:
                    team: { type: string, enum: [payments], examples: [billing] }
                """));

        assertEquals(List.of(
                "MCP tool 'crm.ask-account' input parameter 'size': example '2.5' does not match"
                        + " type 'integer'",
                "MCP tool 'crm.ask-account' input parameter 'severity': example 'sev9' is not"
                        + " one of the enum values",
                "MCP tool 'crm.ask-account' input parameter 'labels': property 'team': example"
                        + " 'billing' is not one of the enum values"),
                errors);
    }

    /**
     * The engine and the Polychro rule {@code ikanos-mcp-tool-input-examples} agree on the rule's
     * fixtures: no example of the compliant one is refused, and every example the rule reports in
     * the violations one is refused, but for the unquoted integer beyond 2^53, which only the
     * linter cannot read exactly.
     */
    @Test
    void findExampleErrorsShouldAgreeWithTheLintRuleFixtures() throws Exception {
        Path rules = Path.of("../ikanos-spec/src/test/resources/rules");
        IkanosSpec compliant = IkanosMappers.lenientYaml().readValue(
                rules.resolve("polychro-mcp-tool-input-examples-compliant.yaml").toFile(),
                IkanosSpec.class);
        IkanosSpec violations = IkanosMappers.lenientYaml().readValue(
                rules.resolve("polychro-mcp-tool-input-examples-violations.yaml").toFile(),
                IkanosSpec.class);

        assertEquals(List.of(), InputDefaults.findExampleErrors(compliant));
        List<String> flagged = InputDefaults.findExampleErrors(violations).stream()
                .map(error -> error.replaceAll(".*input parameter '([^']+)'.*", "$1"))
                .toList();
        assertEquals(List.of("severity", "impact", "impact", "replicas", "page_size", "region",
                "page", "labels"), flagged);
    }

    @Test
    void findErrorsShouldAcceptAnIntegralDecimalDefaultOnAnIntegerInput() throws Exception {
        List<String> errors = InputDefaults.findErrors(spec("""
                size: { type: integer, description: Size, required: false, default: 25.0 }
                """));

        assertEquals(2, errors.size(), "Only the flow and consumed tool defaults fail: " + errors);
    }

    @Test
    void validateShouldThrowListingTheMismatchedExamples() throws Exception {
        IkanosSpec spec = spec("""
                size: { type: integer, description: Size, examples: [2.5] }
                """);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> InputDefaults.validate(spec));

        assertTrue(error.getMessage().contains("Invalid input example: MCP tool"
                + " 'crm.ask-account' input parameter 'size': example '2.5'"), error.getMessage());
    }

    @Test
    void findWarningsShouldReportDefaultOnRequiredParameter() throws Exception {
        List<String> warnings = InputDefaults.findWarnings(spec("""
                period: { type: string, description: Period, required: true, default: "x" }
                """));

        assertEquals(List.of("MCP tool 'crm.ask-account' input parameter 'period' is required,"
                + " so its default never applies"), warnings);
    }

    @Test
    void validateShouldThrowListingTheMismatchedDefaults() throws Exception {
        IkanosSpec spec = spec("""
                period: { type: string, description: Period, required: false, default: 7 }
                """);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> InputDefaults.validate(spec));

        assertTrue(error.getMessage().contains("'crm.ask-account'"), error.getMessage());
    }

    private static IkanosSpec grpcSpec(String procedureParameters) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: grpc
                      namespace: orders
                      description: Order service.
                      endpoint: "http://localhost:50051"
                      descriptorSet: ./contracts/orders.binpb
                      service: io.example.orders.v1.OrderService
                      procedures:
                        list-orders:
                          description: Lists orders.
                          rpc: ListOrders
                          inputParameters:
                %s
                  exposes:
                    - type: mcp
                      port: 0
                      namespace: shop
                      tools:
                        list:
                          description: "List."
                          call: orders.list-orders
                """.formatted(IKANOS, procedureParameters.indent(12).stripTrailing());
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }

    @Test
    void findErrorsShouldCheckTheDefaultsOfAContractOperation() throws Exception {
        List<String> errors = InputDefaults.findErrors(grpcSpec("""
                page-size: { type: integer, description: Size, wireName: pageSize, default: "50" }
                """));

        assertEquals(List.of("consumed grpc procedure 'orders.list-orders' input parameter"
                + " 'page-size': default does not match type 'integer'"), errors);
    }

    @Test
    void findExampleErrorsShouldCheckTheExamplesOfAContractOperation() throws Exception {
        List<String> errors = InputDefaults.findExampleErrors(grpcSpec("""
                state: { type: string, description: State, enum: [open, closed], examples: [lost] }
                """));

        assertEquals(List.of("consumed grpc procedure 'orders.list-orders' input parameter"
                + " 'state': example 'lost' is not one of the enum values"), errors);
    }
}
