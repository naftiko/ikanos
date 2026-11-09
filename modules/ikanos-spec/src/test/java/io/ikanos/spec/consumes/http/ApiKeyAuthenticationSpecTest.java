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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.ikanos.spec.util.IkanosMappers;

/**
 * #840: one rule for where an API key goes, on the exposed and the consumed side. The schema
 * says placement "Defaults to `header` when omitted".
 */
class ApiKeyAuthenticationSpecTest {

    @ParameterizedTest(name = "placement: {0}")
    @NullSource
    @ValueSource(strings = {"header", "Header"})
    void isQueryPlacementShouldBeFalseForTheHeaderOrNoPlacement(String placement) {
        assertFalse(new ApiKeyAuthenticationSpec("X-Api-Key", placement, "k1").isQueryPlacement());
    }

    @ParameterizedTest(name = "placement: {0}")
    @ValueSource(strings = {"query", "Query", "QUERY"})
    void isQueryPlacementShouldBeTrueForTheQuery(String placement) {
        assertTrue(new ApiKeyAuthenticationSpec("api_key", placement, "k1").isQueryPlacement());
    }

    @Test
    void isQueryPlacementShouldNotBeSerialized() throws Exception {
        String json = IkanosMappers.json()
                .writeValueAsString(new ApiKeyAuthenticationSpec("X-Api-Key", "query", "k1"));

        assertFalse(json.contains("queryPlacement"), json);
    }
}
