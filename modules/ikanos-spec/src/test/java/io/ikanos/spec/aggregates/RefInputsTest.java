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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;

/**
 * The inputs of a {@code ref} unit: its own, or its flow's, placed in the request for a REST
 * operation (#1039).
 */
class RefInputsTest {

    @Test
    void ownOrFlowShouldReturnTheOwnInputsWhenTheUnitDeclaresSome() {
        List<InputParameterSpec> own = List.of(input("code", null));

        List<InputParameterSpec> inputs = RefInputs.ownOrFlow(own, "fleet.get-ship",
                () -> List.of(input("imo", null)));

        assertSame(own, inputs);
    }

    @Test
    void ownOrFlowShouldReturnTheFlowInputsWhenTheUnitDeclaresNone() {
        InputParameterSpec imo = input("imo", null);

        List<InputParameterSpec> inputs = RefInputs.ownOrFlow(List.of(), "fleet.get-ship",
                () -> List.of(imo));

        assertEquals(List.of(imo), inputs);
    }

    @Test
    void ownOrFlowShouldReturnNoInputWhenTheUnitUsesNoRef() {
        assertTrue(RefInputs.ownOrFlow(null, null, () -> {
            throw new AssertionError("the flow must not be looked up");
        }).isEmpty());
    }

    @Test
    void forRestOperationShouldPlaceAFlowInputNamedInThePathInThePath() {
        RestServerResourceSpec resource = resource("/ships/{imo}", "GET");

        InputParameterSpec placed = RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("imo", false))).get(0);

        assertEquals("path", placed.getIn());
        assertTrue(placed.isRequired(), "a path parameter is always required");
    }

    @Test
    void forRestOperationShouldPlaceAFlowInputNamedInAMustachePathInThePath() {
        RestServerResourceSpec resource = resource("/ships/{{imo}}/crew", "POST");

        InputParameterSpec placed = RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("imo", null))).get(0);

        assertEquals("path", placed.getIn());
    }

    @Test
    void forRestOperationShouldPlaceAHyphenatedFlowInputNamedInThePathInThePath() {
        RestServerResourceSpec resource = resource("/ships/{{ship-id}}", "GET");

        InputParameterSpec placed = RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("ship-id", null))).get(0);

        assertEquals("path", placed.getIn());
    }

    @ParameterizedTest
    @CsvSource({"GET, query", "DELETE, query", "HEAD, query", "get, query", "POST, body",
            "PUT, body", "PATCH, body"})
    void forRestOperationShouldPlaceOtherFlowInputsByMethod(String method, String expected) {
        RestServerResourceSpec resource = resource("/ships", method);

        InputParameterSpec placed = RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("flag", null))).get(0);

        assertEquals(expected, placed.getIn());
    }

    @Test
    void forRestOperationShouldReadOnlyTheBodyMemberOfTheInputsName() {
        RestServerResourceSpec resource = resource("/ships", "POST");

        InputParameterSpec placed = RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("name", null))).get(0);

        assertEquals("$['name']", placed.getValue());
    }

    @Test
    void forRestOperationShouldKeepAFlowInputOptionalUnlessItIsRequired() {
        RestServerResourceSpec resource = resource("/ships", "GET");

        List<InputParameterSpec> placed = RefInputs.forRestOperation(resource,
                operation(resource), () -> List.of(input("flag", null), input("imo", true)));

        assertFalse(placed.get(0).isRequiredOrDefault(true));
        assertTrue(placed.get(1).isRequiredOrDefault(false));
    }

    @Test
    void forRestOperationShouldNotChangeTheFlowInputs() {
        RestServerResourceSpec resource = resource("/ships/{imo}", "GET");
        InputParameterSpec imo = input("imo", null);

        RefInputs.forRestOperation(resource, operation(resource), () -> List.of(imo));

        assertNull(imo.getIn());
        assertNull(imo.getDeclaredRequired());
    }

    @Test
    void forRestOperationShouldInheritNothingWhenTheOperationDeclaresInputs() {
        RestServerResourceSpec resource = resource("/ships/{imo}", "GET");
        RestServerOperationSpec operation = operation(resource);
        operation.setInputParameters(Map.of("code", input("code", null)));

        assertTrue(RefInputs.forRestOperation(resource, operation,
                () -> List.of(input("imo", null))).isEmpty());
    }

    @Test
    void forRestOperationShouldInheritNothingWhenTheResourceDeclaresInputs() {
        RestServerResourceSpec resource = resource("/ships/{imo}", "GET");
        resource.setInputParameters(Map.of("imo", input("imo", null)));

        assertTrue(RefInputs.forRestOperation(resource, operation(resource),
                () -> List.of(input("imo", null))).isEmpty());
    }

    @Test
    void forRestOperationShouldInheritNothingWhenTheOperationUsesNoRef() {
        RestServerResourceSpec resource = resource("/ships", "GET");
        RestServerOperationSpec operation = operation(resource);
        operation.setRef(null);

        assertTrue(RefInputs.forRestOperation(resource, operation, () -> {
            throw new AssertionError("the flow must not be looked up");
        }).isEmpty());
    }

    private static RestServerResourceSpec resource(String path, String method) {
        RestServerResourceSpec resource = new RestServerResourceSpec(path);
        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.setMethod(method);
        operation.setName("op");
        operation.setRef("fleet.flow");
        resource.setOperations(Map.of("op", operation));
        return resource;
    }

    private static RestServerOperationSpec operation(RestServerResourceSpec resource) {
        return resource.getOperations().get("op");
    }

    private static InputParameterSpec input(String name, Boolean required) {
        InputParameterSpec input = new InputParameterSpec();
        input.setName(name);
        input.setType("string");
        input.setDeclaredRequired(required);
        return input;
    }
}
