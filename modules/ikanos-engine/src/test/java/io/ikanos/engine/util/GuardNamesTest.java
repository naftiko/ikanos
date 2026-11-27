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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GuardNames}: a dashed name is written in snake_case, and a name that
 * matches more than one declared name is refused.
 */
class GuardNamesTest {

    @Test
    void matchesShouldReadADashedNameFromItsSnakeForm() {
        assertEquals(List.of("is-admin"), GuardNames.matches(Set.of("is-admin", "silent"),
                "is_admin"));
        assertEquals(List.of("silent"), GuardNames.matches(Set.of("is-admin", "silent"),
                "silent"));
        assertEquals(List.of(), GuardNames.matches(Set.of("is-admin"), "isadmin"));
    }

    @Test
    void matchesShouldReturnEveryNameASnakeNameMatches() {
        assertEquals(List.of("is-admin", "is_admin"),
                GuardNames.matches(Set.of("is_admin", "is-admin"), "is_admin"));
        assertEquals(List.of("a-b_c", "a_b-c"),
                GuardNames.matches(Set.of("a-b_c", "a_b-c"), "a_b_c"));
    }

    @Test
    void matchesShouldReadAKebabNameOnlyAsItself() {
        assertEquals(List.of("is-admin"), GuardNames.matches(Set.of("is-admin", "is_admin"),
                "is-admin"));
    }

    @Test
    void dashedNameErrorShouldGiveTheSnakeForm() {
        assertEquals("'is-admin' is written with '-': write it 'is_admin'",
                GuardNames.dashedNameError("is-admin"));
        // Only the declared name: the other segments are keys of its value
        assertEquals("'get-ship.crew-size' is written with '-': write it 'get_ship.crew-size'",
                GuardNames.dashedNameError("get-ship.crew-size"));
        assertNull(GuardNames.dashedNameError("get_ship.crew-size"));
    }

    @Test
    void lookupShouldReadTheDashedValueOfASnakeName() {
        Map<String, Object> values = new HashMap<>();
        values.put("is-admin", true);

        assertEquals(true, GuardNames.lookup(values, "is_admin"));
        assertNull(GuardNames.lookup(values, "missing"));
        assertNull(GuardNames.lookup(null, "is_admin"));
    }

    @Test
    void lookupShouldRefuseANameThatMatchesMoreThanOneValue() {
        Map<String, Object> values = new HashMap<>();
        values.put("is-admin", true);
        values.put("is_admin", false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GuardNames.lookup(values, "is_admin"));
        assertEquals("'is_admin' matches more than one name (is-admin, is_admin), since '-' is"
                + " written '_': rename one of them", error.getMessage());
    }
}
