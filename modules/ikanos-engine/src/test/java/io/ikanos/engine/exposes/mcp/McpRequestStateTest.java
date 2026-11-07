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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

public class McpRequestStateTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private static SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static ObjectNode answers() {
        ObjectNode answers = JSON.createObjectNode();
        answers.putObject("steps/confirm").put("action", "accept");
        return answers;
    }

    @Test
    void openShouldReturnTheSealedAnswersAndPendingKeys() throws Exception {
        McpRequestState state = new McpRequestState(key(), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = state.seal("agent-7", "digest", answers(), List.of("steps/approve"));

        McpRequestState.Content content = state.open(token, "agent-7", "digest");

        assertEquals(answers(), content.answers());
        assertEquals(List.of("steps/approve"), content.pending());
    }

    @Test
    void openShouldRejectAnotherPrincipal() throws Exception {
        McpRequestState state = new McpRequestState(key(), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = state.seal("agent-7", "digest", answers(), List.of());

        assertThrows(IllegalArgumentException.class, () -> state.open(token, "agent-8", "digest"));
    }

    @Test
    void openShouldRejectAnotherRequest() throws Exception {
        McpRequestState state = new McpRequestState(key(), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = state.seal("agent-7", "digest", answers(), List.of());

        assertThrows(IllegalArgumentException.class, () -> state.open(token, "agent-7", "other"));
    }

    @Test
    void openShouldRejectAnExpiredState() throws Exception {
        SecretKey key = key();
        String token = new McpRequestState(key, Clock.fixed(NOW, ZoneOffset.UTC))
                .seal("agent-7", "digest", answers(), List.of());
        McpRequestState later = new McpRequestState(key,
                Clock.fixed(NOW.plus(McpRequestState.TTL).plus(Duration.ofSeconds(1)),
                        ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> later.open(token, "agent-7", "digest"));
    }

    @Test
    void openShouldRejectStateSealedWithAnotherKey() throws Exception {
        String token = new McpRequestState(key(), Clock.fixed(NOW, ZoneOffset.UTC))
                .seal("agent-7", "digest", answers(), List.of());
        McpRequestState other = new McpRequestState(key(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> other.open(token, "agent-7", "digest"));
    }

    @Test
    void openShouldRejectGarbage() {
        McpRequestState state = McpRequestState.withRandomKey();
        assertThrows(IllegalArgumentException.class, () -> state.open("not-base64!", "a", "d"));
        assertThrows(IllegalArgumentException.class, () -> state.open("AAAA", "a", "d"));
    }

    @Test
    void digestShouldIgnoreTheOrderOfObjectKeys() {
        String a = McpRequestState.digest("tools/call", "clear",
                JSON.readTree("{\"voyageId\":\"VOY-1\",\"options\":{\"x\":1,\"y\":[1,2]}}"));
        String b = McpRequestState.digest("tools/call", "clear",
                JSON.readTree("{\"options\":{\"y\":[1,2],\"x\":1},\"voyageId\":\"VOY-1\"}"));
        String c = McpRequestState.digest("tools/call", "clear",
                JSON.readTree("{\"voyageId\":\"VOY-2\"}"));

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, McpRequestState.digest("tools/call", "clear", null));
    }
}
