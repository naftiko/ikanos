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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

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
}
