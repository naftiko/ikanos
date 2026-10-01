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
package io.ikanos.engine.exposes.mcp;

import io.ikanos.engine.LogCapture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class McpErrorsTest {

    private static final String SENSITIVE_DETAIL = "jdbc:postgresql://internal-db:5432/orders";

    @Test
    public void internalErrorShouldReturnGenericMessageWithoutExceptionDetail() {
        ObjectNode envelope = McpErrors.internalError(IntNode.valueOf(7),
                "Error processing request", new IllegalStateException(SENSITIVE_DETAIL));

        String message = envelope.path("error").path("message").asText();
        assertEquals(-32603, envelope.path("error").path("code").asInt());
        assertEquals(7, envelope.path("id").asInt());
        assertTrue(message.startsWith("Internal error (reference: "), message);
        assertFalse(message.contains(SENSITIVE_DETAIL), message);
        assertFalse(message.contains("IllegalStateException"), message);
    }

    @Test
    public void internalErrorShouldLogDetailUnderTheReferenceReturnedInTheMessage() {
        LogCapture logs = new LogCapture();

        try {
            ObjectNode envelope = McpErrors.internalError(null,
                    "Error processing request", new IllegalStateException(SENSITIVE_DETAIL));

            String message = envelope.path("error").path("message").asText();
            String id = message.substring(message.indexOf("reference: ") + "reference: ".length(),
                    message.length() - 1);
            List<String> lines = logs.messages();
            assertTrue(envelope.path("id").isNull());
            assertTrue(lines.stream().anyMatch(
                    m -> m.contains(id) && m.contains(SENSITIVE_DETAIL)), lines.toString());
        } finally {
            logs.close();
        }
    }
}
