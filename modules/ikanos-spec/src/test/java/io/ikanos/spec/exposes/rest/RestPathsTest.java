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
package io.ikanos.spec.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestPathsTest {

    @Test
    void toUriTemplateShouldConvertMustachePlaceholdersIncludingHyphenatedNames() {
        assertEquals("/fleets/{fleet-id}/ships/{imo}",
                RestPaths.toUriTemplate("/fleets/{{fleet-id}}/ships/{{imo}}"));
    }

    @Test
    void toUriTemplateShouldKeepUriTemplatePlaceholders() {
        assertEquals("/ships/{imo}", RestPaths.toUriTemplate("/ships/{imo}"));
    }

    @Test
    void toUriTemplateShouldReturnNullForANullPath() {
        assertNull(RestPaths.toUriTemplate(null));
    }

    @Test
    void placeholdersShouldListTheNamesOfBothFormsInOrder() {
        assertEquals(List.of("fleet-id", "imo", "crew_id"), List.copyOf(
                RestPaths.placeholders("/fleets/{{fleet-id}}/ships/{imo}/crew/{{crew_id}}")));
    }

    @Test
    void placeholdersShouldBeEmptyForAPathWithoutPlaceholders() {
        assertTrue(RestPaths.placeholders("/ships").isEmpty());
        assertTrue(RestPaths.placeholders(null).isEmpty());
    }
}
