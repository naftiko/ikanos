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
package io.ikanos.engine.consumes.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.ikanos.durable.NodePath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class UpstreamCallTest {

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "steps/confirm", "confirm@2"})
    void keyOfShouldKeepAnUpstreamKeyThatIsAValidPath(String key) {
        assertEquals(key, UpstreamCall.keyOf(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/confirm", "confirm/", "a//b", "", " ", "a/ /b", "~confirm", "~"})
    void keyOfShouldEncodeAnUpstreamKeyThatIsNoPathOrLooksEncoded(String key) {
        String shown = UpstreamCall.keyOf(key);

        assertTrue(shown.startsWith("~"), shown);
        assertDoesNotThrow(() -> new NodePath("steps/customs/" + shown));
        assertEquals(key, UpstreamCall.upstreamKey(shown), "The upstream gets its own key back");
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "steps/confirm", "~not base64!"})
    void upstreamKeyShouldKeepAKeyThatIsNotEncoded(String key) {
        assertEquals(key, UpstreamCall.upstreamKey(key));
    }
}
