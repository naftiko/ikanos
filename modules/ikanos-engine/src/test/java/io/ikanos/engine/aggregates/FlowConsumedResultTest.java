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
package io.ikanos.engine.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;

class FlowConsumedResultTest {

    private static final BigDecimal TOTAL = new BigDecimal("12345678901234567890.12");

    @Test
    void passedThroughResultShouldKeepItsNativeDocument() throws Exception {
        JsonNode document = JsonNodeFactory.instance.objectNode().put("total", TOTAL);
        FlowConsumedResult result = new FlowConsumedResult(null,
                new FlowResult(new Stub(document, true), null, null));

        assertTrue(result.nativeDocument());
        assertEquals(TOTAL, result.document().get("total").decimalValue());
    }

    @Test
    void passedThroughTextResultShouldStayText() throws Exception {
        FlowConsumedResult result = new FlowConsumedResult(null,
                new FlowResult(new Stub(null, false), null, null));

        assertFalse(result.nativeDocument());
    }

    @Test
    void mappedOutputShouldKeepItsDecimalsExact() throws Exception {
        FlowConsumedResult result = new FlowConsumedResult(null, new FlowResult(
                new Stub(null, false), "{\"total\":12345678901234567890.12}", null));

        assertTrue(result.nativeDocument());
        assertEquals(TOTAL, result.document().get("total").decimalValue());
    }

    /** A successful consumed result with a fixed document. */
    private record Stub(JsonNode document, boolean nativeDocument) implements ConsumedResult {

        @Override
        public Outcome outcome() {
            return Outcome.SUCCESS;
        }

        @Override
        public int status() {
            return 200;
        }

        @Override
        public String reason() {
            return "OK";
        }

        @Override
        public boolean hasBody() {
            return document != null;
        }

        @Override
        public String mediaType() {
            return "application/json";
        }

        @Override
        public String text() {
            return document != null ? document.toString() : null;
        }

        @Override
        public byte[] bytes(long maxBytes) {
            return null;
        }

        @Override
        public ConsumedOperationView operation() {
            return null;
        }

        @Override
        public void release() {
        }
    }
}
