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
package io.ikanos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.exposes.mcp.McpRequestState;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.node.ObjectNode;

class CapabilitySealingKeyTest {

    private static final byte[] KEY_BYTES = new byte[32];

    static {
        Arrays.fill(KEY_BYTES, (byte) 7);
    }

    private static DurabilitySpec declaring(String sealingKey) {
        DurabilitySpec spec = new DurabilitySpec("none");
        spec.setSealingKey(sealingKey);
        return spec;
    }

    @Test
    void aKeyReferencedFromBindsShouldBeResolved() {
        String encoded = Base64.getEncoder().encodeToString(KEY_BYTES);

        SecretKey key = Capability.sealingKey(declaring("{{IKANOS_SEALING_KEY}}"),
                Map.of("IKANOS_SEALING_KEY", encoded));

        assertArrayEquals(KEY_BYTES, key.getEncoded());
        assertEquals("AES", key.getAlgorithm());
    }

    @Test
    void withoutADeclaredKeyEachCapabilityShouldDrawItsOwn() {
        SecretKey first = Capability.sealingKey(null, Map.of());
        SecretKey second = Capability.sealingKey(new DurabilitySpec("memory"), Map.of());

        assertEquals(32, first.getEncoded().length);
        assertFalse(Arrays.equals(first.getEncoded(), second.getEncoded()));
    }

    @Test
    void anUnresolvedReferenceShouldFailTheLoad() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capability.sealingKey(declaring("{{MISSING}}"), Map.of()));

        assertTrue(e.getMessage().contains("binds"), e.getMessage());
    }

    @Test
    void aKeyOfTheWrongSizeShouldFailWithoutEchoingIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[10]);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capability.sealingKey(declaring("{{K}}"), Map.of("K", shortKey)));

        assertTrue(e.getMessage().contains("80 bits"), e.getMessage());
        assertFalse(e.getMessage().contains(shortKey));
    }

    @Test
    void textThatIsNotBase64ShouldFailWithoutEchoingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpRequestState.parseKey("not a key!"));

        assertFalse(e.getMessage().contains("not a key!"));
    }

    @Test
    void urlSafeBase64ShouldBeAccepted() {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) 0xFB);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertArrayEquals(bytes, McpRequestState.parseKey(urlSafe).getEncoded());
    }

    @Test
    void replicasSharingTheKeyShouldOpenEachOthersState() {
        SecretKey key = McpRequestState.parseKey(Base64.getEncoder().encodeToString(KEY_BYTES));
        McpRequestState replicaA = McpRequestState.withKey(key);
        McpRequestState replicaB = McpRequestState.withKey(
                McpRequestState.parseKey(Base64.getEncoder().encodeToString(KEY_BYTES)));
        ObjectNode answers = IkanosMappers.json().createObjectNode();
        answers.put("steps/confirm", true);

        String token = replicaA.seal("agent-7", "digest", answers, List.of("steps/next"));
        McpRequestState.Content content = replicaB.open(token, "agent-7", "digest");

        assertTrue(content.answers().path("steps/confirm").asBoolean());
        assertEquals(List.of("steps/next"), content.pending());
        assertThrows(IllegalArgumentException.class,
                () -> McpRequestState.withRandomKey().open(token, "agent-7", "digest"));
    }

    @Test
    void aReplicaRunningAnotherSpecShouldRejectTheState() {
        SecretKey key = McpRequestState.parseKey(Base64.getEncoder().encodeToString(KEY_BYTES));
        String token = McpRequestState.withKey(key, "spec-v1").seal("agent-7", "digest",
                IkanosMappers.json().createObjectNode(), List.of("steps/confirm"));

        assertEquals(List.of("steps/confirm"),
                McpRequestState.withKey(key, "spec-v1").open(token, "agent-7", "digest")
                        .pending());
        assertThrows(IllegalArgumentException.class,
                () -> McpRequestState.withKey(key, "spec-v2").open(token, "agent-7", "digest"));
    }

    @Test
    void theSpecDigestShouldFollowTheSpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "%s"
                  description: "Spec digest test"
                capability:
                  exposes:
                  - type: control
                    address: localhost
                    port: 0
                """;
        String version = io.ikanos.spec.util.VersionHelper.getSchemaVersion();
        IkanosSpec first = IkanosMappers.yaml().readValue(yaml.formatted(version, "A"),
                IkanosSpec.class);
        IkanosSpec same = IkanosMappers.yaml().readValue(yaml.formatted(version, "A"),
                IkanosSpec.class);
        IkanosSpec other = IkanosMappers.yaml().readValue(yaml.formatted(version, "B"),
                IkanosSpec.class);

        assertEquals(Capability.specDigest(first), Capability.specDigest(same));
        assertFalse(Capability.specDigest(first).equals(Capability.specDigest(other)));
    }
}
