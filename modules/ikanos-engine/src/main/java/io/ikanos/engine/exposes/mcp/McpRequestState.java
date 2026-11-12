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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The {@code requestState} of a stateless multi round-trip request (MCP {@code 2026-07-28}).
 *
 * <p>The state passes through the client, so it is attacker-controlled input. It is sealed with
 * AES-GCM, which protects both its integrity and the answers it carries, and bound to the
 * principal, an expiry, a digest of the request (method, tool and arguments) and the digest of the
 * capability spec (the scope). State that fails any check is rejected. It carries the answers
 * collected so far and the keys still outstanding; it never carries step outputs or secrets.</p>
 *
 * <p>The key is the capability's sealing key ({@code durability.sealingKey} on the control port,
 * resolved from {@code binds}), shared by its MCP adapters and by replicas that declare the same
 * key. Without one, the key is random per process, so state does not survive a restart: the
 * client then gets an error and starts the call again.</p>
 */
public final class McpRequestState {

    /** How long a state stays valid. */
    static final Duration TTL = Duration.ofMinutes(10);

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private final SecretKey key;
    private final Clock clock;
    private final String scope;

    /**
     * The content of a verified state.
     *
     * @param answers the answers collected so far, by input request key
     * @param pending the keys asked in the last round, still outstanding
     */
    public record Content(ObjectNode answers, List<String> pending) {
    }

    McpRequestState(SecretKey key, Clock clock) {
        this(key, clock, "");
    }

    McpRequestState(SecretKey key, Clock clock, String scope) {
        this.key = key;
        this.clock = clock;
        this.scope = scope;
    }

    /** A state sealer with a fresh random key. */
    public static McpRequestState withRandomKey() {
        return withKey(randomKey());
    }

    /** A state sealer with the given key. */
    public static McpRequestState withKey(SecretKey key) {
        return withKey(key, "");
    }

    /**
     * A state sealer with the given key, whose states only open under the same scope. The scope
     * is the digest of the capability spec: a state sealed by a replica running another version
     * of the capability is rejected, so its answers are never replayed into changed steps.
     */
    public static McpRequestState withKey(SecretKey key, String scope) {
        return new McpRequestState(key, Clock.systemUTC(), scope);
    }

    /** A fresh random 256-bit AES key. */
    public static SecretKey randomKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, RANDOM);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is not available", e);
        }
    }

    /**
     * Parses a declared sealing key: base64 (standard or URL-safe) of 16, 24 or 32 bytes.
     *
     * @throws IllegalArgumentException when the value is not such a key; the message never
     *         carries the value
     */
    public static SecretKey parseKey(String base64) {
        byte[] bytes;
        try {
            String text = base64.trim();
            bytes = text.indexOf('-') >= 0 || text.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(text)
                    : Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("durability.sealingKey is not valid base64");
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("durability.sealingKey must be an AES key of 128, "
                    + "192 or 256 bits; it has " + (bytes.length * 8) + " bits");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** Seals the answers so far and the outstanding keys, bound to a principal and a request. */
    public String seal(String principal, String requestDigest, ObjectNode answers,
            List<String> pending) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("p", principal);
        payload.put("d", scope + "\n" + requestDigest);
        payload.put("e", clock.millis() + TTL.toMillis());
        payload.set("a", answers);
        payload.set("k", MAPPER.valueToTree(pending));
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(MAPPER.writeValueAsBytes(payload));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal requestState", e);
        }
    }

    /**
     * Opens a state presented by a client.
     *
     * @throws IllegalArgumentException when the state is malformed, tampered with, expired, or
     *         bound to another principal or request
     */
    public Content open(String token, String principal, String requestDigest) {
        JsonNode payload;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length <= IV_BYTES) {
                throw new IllegalArgumentException("Invalid requestState");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            payload = MAPPER.readTree(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid requestState", e);
        }
        if (!principal.equals(payload.path("p").asString())
                || !(scope + "\n" + requestDigest).equals(payload.path("d").asString())) {
            throw new IllegalArgumentException("requestState does not match this request");
        }
        if (clock.millis() > payload.path("e").asLong()) {
            throw new IllegalArgumentException("requestState has expired");
        }
        List<String> pending = new ArrayList<>();
        payload.path("k").forEach(key -> pending.add(key.asString()));
        JsonNode answers = payload.path("a");
        return new Content(answers.isObject() ? (ObjectNode) answers : MAPPER.createObjectNode(),
                pending);
    }

    /**
     * Digest of a request: method, name and arguments, with object keys sorted so that the same
     * arguments in another order give the same digest.
     */
    public static String digest(String method, String name, JsonNode arguments) {
        StringBuilder text = new StringBuilder(method).append('\n').append(name).append('\n');
        canonical(arguments, text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static void canonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            out.append("null");
        } else if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            out.append('{');
            sorted.forEach((name, value) -> {
                out.append(MAPPER.writeValueAsString(name)).append(':');
                canonical(value, out);
                out.append(',');
            });
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            node.forEach(item -> {
                canonical(item, out);
                out.append(',');
            });
            out.append(']');
        } else {
            out.append(MAPPER.writeValueAsString(node));
        }
    }
}
