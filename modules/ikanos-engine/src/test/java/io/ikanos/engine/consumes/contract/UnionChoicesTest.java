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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnionChoicesTest {

    @Test
    void chooseShouldRefuseTwoMembersOfOneUnionAtOnePlace() {
        UnionChoices choices = new UnionChoices("get-item");
        choices.choose("id-text", List.of("Key", "id"), "AttributeValue", "S");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> choices.choose("id-number", List.of("Key", "id"), "AttributeValue", "N"));

        assertTrue(e.getMessage().contains("'id-text' and 'id-number'"), e.getMessage());
        assertTrue(e.getMessage().contains("at 'Key.id'"), e.getMessage());
    }

    @Test
    void chooseShouldAcceptOneMemberPerPlaceAndTheSameMemberTwice() {
        UnionChoices choices = new UnionChoices("get-item");

        assertDoesNotThrow(() -> {
            choices.choose("partition", List.of("Key", "pk"), "AttributeValue", "S");
            choices.choose("sort", List.of("Key", "sk"), "AttributeValue", "N");
            choices.choose("filter", List.of("Filter"), "AttributeValue", "S");
            choices.choose("prefix", List.of("Key", "pk"), "AttributeValue", "S");
        });
    }

    @Test
    void chooseShouldTellUnionsOfOneMessageApart() {
        UnionChoices choices = new UnionChoices("cancel-order");
        choices.choose("by-id", List.of(), "target", "id");

        assertDoesNotThrow(() -> choices.choose("by-card", List.of(), "payment", "card"));
        assertThrows(IllegalArgumentException.class,
                () -> choices.choose("by-ref", List.of(), "target", "reference"));
    }
}
