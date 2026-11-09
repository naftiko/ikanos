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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The idempotency key a consumed call sends to an upstream that deduplicates by key.
 *
 * <p>Inside a journaled run, the key is a name-based UUID (version 5) of the action's identity,
 * its execution ID and path: every attempt of the action sends the same key, also after a
 * restart, so the upstream makes the write once. It never reveals step names or execution IDs,
 * and fits the length every known upstream accepts. Outside a journal, a call has no identity to
 * name, so it draws a random UUID (Execution &amp; Resolution Model §7.4.1).</p>
 */
public final class IdempotencyKeys {

    /** The RFC 9562 namespace for URLs, under which the action's identity is named. */
    private static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    /** Prefix of the name, so a key cannot collide with another use of the URL namespace. */
    static final String NAME_PREFIX = "urn:ikanos:action:";

    private IdempotencyKeys() {
    }

    /** The key for the consumed call in progress on this thread. */
    public static String forCurrentCall() {
        return DurableScope.actionIdentity()
                .map(identity -> nameBased(NAME_PREFIX + identity).toString())
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    /** The version 5 UUID of {@code name} in the URL namespace (RFC 9562, §5.5). */
    static UUID nameBased(String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every Java platform", e);
        }
        ByteBuffer namespace = ByteBuffer.allocate(16);
        namespace.putLong(NAMESPACE.getMostSignificantBits());
        namespace.putLong(NAMESPACE.getLeastSignificantBits());
        sha1.update(namespace.array());
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bits.getLong(), bits.getLong());
    }
}
