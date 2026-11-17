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
package io.ikanos.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

class JsonPayloadCodecTest {

    private static final BigDecimal AMOUNT = new BigDecimal("12345678901234567890.12");

    @Test
    void decodeShouldKeepDecimalsExact() {
        JsonPayloadCodec codec = new JsonPayloadCodec();
        ObjectNode value = IkanosMappers.json().createObjectNode().put("amount", AMOUNT);

        JsonNode decoded = (JsonNode) codec.decode(codec.encode(value));

        assertEquals(0, AMOUNT.compareTo(decoded.get("amount").decimalValue()));
    }

    @Test
    void restoredResponseShouldBeReadAsItsTree() {
        JsonPayloadCodec codec = new JsonPayloadCodec();
        ObjectNode row = IkanosMappers.json().createObjectNode().put("amount", AMOUNT);
        JsonNode journaled = (JsonNode) codec.decode(codec.encode(
                RestoredResult.journal(IkanosMappers.json().createObjectNode(),
                        new RestoredResult(row))));

        RestoredResult restored = RestoredResult.responseOf(journaled);

        assertTrue(restored.nativeDocument());
        assertEquals(0, AMOUNT.compareTo(restored.document().get("amount").decimalValue()));
    }
}
