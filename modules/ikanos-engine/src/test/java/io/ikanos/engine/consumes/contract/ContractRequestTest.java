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
}
