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
package io.ikanos.engine.consumes.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import io.ikanos.spec.InputParameterSpec;

class ContractRequestTest {

    private static InputParameterSpec input(String name, String type, boolean required,
            String wireName) {
        InputParameterSpec input = new InputParameterSpec();
        input.setName(name);
        input.setType(type);
        input.setRequired(required);
        input.setWireName(wireName);
        return input;
    }

    @Test
    void buildShouldWriteAnInputAtItsName() {
        ContractRequest request = ContractRequest.build(
                List.of(input("id", "string", true, null)), Map.of("id", "42"), "op");

        assertEquals("{\"id\":\"42\"}", request.tree().toString());
    }

    @Test
    void buildShouldWriteANestedWireNamePath() {
        ContractRequest request = ContractRequest.build(
                List.of(input("title", "string", false, "input.title"),
                        input("team-id", "string", false, "input.teamId")),
                Map.of("title", "Bug", "team-id", "t1"), "op");

        assertEquals("{\"input\":{\"title\":\"Bug\",\"teamId\":\"t1\"}}",
                request.tree().toString());
    }

    @Test
    void buildShouldLeaveOutAnAbsentOptionalInput() {
        ContractRequest request = ContractRequest.build(
                List.of(input("id", "string", false, null), input("q", "string", false, null)),
                Map.of("q", "x"), "op");

        assertFalse(request.tree().has("id"), "never sent as null");
        assertEquals(Set.of("q"), request.present());
    }

    @Test
    void buildShouldApplyTheDeclaredDefault() {
        InputParameterSpec table = input("table", "string", false, "TableName");
        table.setDefaultValue(JsonNodeFactory.instance.stringNode("customers"));

        ContractRequest request = ContractRequest.build(List.of(table), Map.of(), "op");

        assertEquals("customers", request.tree().get("TableName").asString());
    }

    @Test
    void buildShouldRefuseAMissingRequiredInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContractRequest.build(List.of(input("id", "string", true, null)),
                        Map.of(), "orders.get-order"));

        assertTrue(e.getMessage().contains("'id'") && e.getMessage().contains("orders.get-order"),
                e.getMessage());
    }

    @Test
    void buildShouldConvertTextToTheDeclaredScalarType() {
        ContractRequest request = ContractRequest.build(
                List.of(input("n", "integer", false, null), input("x", "number", false, null),
                        input("b", "boolean", false, null)),
                Map.of("n", "7", "x", "2.5", "b", "TRUE"), "op");

        assertTrue(request.tree().get("n").isIntegralNumber());
        assertEquals("2.5", request.tree().get("x").asString());
        assertTrue(request.tree().get("b").asBoolean());
    }

    @Test
    void buildShouldRefuseTextThatIsNotTheDeclaredType() {
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)), Map.of("n", "7.5"), "op"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void buildShouldRefuseANumberTooLargeToConvertQuickly() {
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)), Map.of("n", "1e20000000"), "op"),
                "as text");
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)),
                Map.of("n", new java.math.BigDecimal("1e20000000")), "op"), "as a number");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void buildShouldRefuseANumberTooSmallToConvertQuickly() {
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)), Map.of("n", "1e-200000000"), "op"),
                "as text");
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)),
                Map.of("n", new java.math.BigDecimal("1e-200000000")), "op"), "as a number");
        assertEquals(new java.math.BigDecimal("0.001"), ContractRequest.boundedNumber(
                new java.math.BigDecimal("0.001")), "a small number with few zeros is kept");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void buildShouldRefuseANumberWithTooManyDigitsToConvertQuickly() {
        String text = "1." + "0".repeat(800_000);

        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("n", "integer", false, null)), Map.of("n", text), "op"));
        assertEquals(new java.math.BigDecimal("1".repeat(1000)), ContractRequest.boundedNumber(
                new java.math.BigDecimal("1".repeat(1000))), "a thousand digits are kept");
    }

    @Test
    void parseNumberShouldTellALongTextThatIsNotANumberFromATooLongNumber() {
        assertThrows(NumberFormatException.class,
                () -> ContractRequest.parseNumber("-".repeat(3000)), "dashes");
        assertThrows(NumberFormatException.class,
                () -> ContractRequest.parseNumber("1.2.3.".repeat(500)), "dotted digits");
        assertThrows(ArithmeticException.class,
                () -> ContractRequest.parseNumber("-1." + "5".repeat(3000) + "e-7"), "a number");
        assertTrue(ContractRequest.isNumberText(".5E+3"));
        assertFalse(ContractRequest.isNumberText("5e"));
    }

    @Test
    void buildShouldWriteNumbersAsTextForAStringInput() {
        ContractRequest request = ContractRequest.build(
                List.of(input("id", "string", false, null)), Map.of("id", 42), "op");

        assertTrue(request.tree().get("id").isString());
    }

    @Test
    void buildShouldRefuseTwoInputsWritingTheSamePath() {
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("a", "string", false, "x"), input("b", "string", false, "x")),
                Map.of("a", "1", "b", "2"), "op"));
    }

    @Test
    void buildShouldRefuseAPathThroughAValue() {
        assertThrows(IllegalArgumentException.class, () -> ContractRequest.build(
                List.of(input("a", "string", false, "x"), input("b", "string", false, "x.y")),
                Map.of("a", "1", "b", "2"), "op"));
    }

    @Test
    void buildShouldParseAnObjectGivenAsJsonText() {
        ContractRequest request = ContractRequest.build(
                List.of(input("filter", "object", false, null)),
                Map.of("filter", "{\"state\":\"open\"}"), "op");

        assertEquals("open", request.tree().get("filter").get("state").asString());
    }

    @Test
    void buildShouldTreatAJsonNullAsAbsent() {
        Map<String, Object> parameters = Map.of("id", NullNode.getInstance());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContractRequest.build(List.of(input("id", "string", true, null)),
                        parameters, "op"));
        assertTrue(e.getMessage().contains("Missing required input 'id'"), e.getMessage());

        ContractRequest request = ContractRequest.build(
                List.of(input("id", "string", false, null)), parameters, "op");
        assertFalse(request.tree().has("id"), "never sent as null");
        assertTrue(request.present().isEmpty());
    }

    @Test
    void buildShouldRefuseAValueThatIsNotTheDeclaredType() {
        Map<String, Object> wrong = Map.of(
                "integer", 2.5,
                "number", true,
                "boolean", 1,
                "object", List.of("a"),
                "array", Map.of("a", 1),
                "string", Map.of("a", 1));
        for (Map.Entry<String, Object> entry : wrong.entrySet()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ContractRequest.build(List.of(input("v", entry.getKey(), false, null)),
                            Map.of("v", entry.getValue()), "op"), entry.getKey());
            assertTrue(e.getMessage().contains("is not a valid " + entry.getKey()),
                    e.getMessage());
        }
    }

    @Test
    void buildShouldAcceptAValueOfTheDeclaredType() {
        ContractRequest request = ContractRequest.build(
                List.of(input("n", "integer", false, null), input("x", "number", false, null),
                        input("b", "boolean", false, null), input("o", "object", false, null),
                        input("a", "array", false, null)),
                Map.of("n", 3.0, "x", 2.5, "b", false, "o", Map.of("k", "v"), "a",
                        List.of(1, 2)), "op");

        assertTrue(request.tree().get("n").isIntegralNumber(), "an integral number is an integer");
        assertEquals(3, request.tree().get("n").asInt());
        assertTrue(request.tree().get("x").isNumber());
        assertFalse(request.tree().get("b").asBoolean());
        assertEquals("v", request.tree().get("o").get("k").asString());
        assertEquals(2, request.tree().get("a").size());
    }
}
