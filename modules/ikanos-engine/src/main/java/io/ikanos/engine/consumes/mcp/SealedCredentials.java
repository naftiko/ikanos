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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.ObjectMapper;

/**
 * The invocation parameters an upstream's credential templates use, such as {@code {{token}}},
 * sealed into the journal of an upstream task so that a cancel with no worker left, after a
 * restart included, can still authenticate its {@code tasks/cancel}.
 *
 * <p>They are sealed with AES-GCM under the capability's sealing key and bound to the consumed
 * adapter's namespace, so the journal never holds them in clear. Without a declared
 * {@code durability.sealingKey}, the key is random per process: after a restart they cannot be
 * opened, and the cancel falls back to the bindings.</p>
 */
final class SealedCredentials {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = IkanosMappers.json();

    private SealedCredentials() {
    }

    /** Seals {@code parameters} for {@code namespace}. */
    static String seal(SecretKey key, String namespace, Map<String, Object> parameters) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(namespace));
            byte[] sealed = cipher.doFinal(JSON.writeValueAsBytes(parameters));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal upstream credentials", e);
        }
    }

    /**
     * Opens parameters sealed for {@code namespace}.
     *
     * @return the parameters, or an empty map when they cannot be opened: sealed under another
     *         key, such as a random key of an earlier process, or for another namespace
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> open(SecretKey key, String namespace, String sealed) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(sealed);
            if (bytes.length <= IV_BYTES) {
                return Map.of();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(aad(namespace));
            return JSON.readValue(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES),
                    LinkedHashMap.class);
        } catch (GeneralSecurityException | RuntimeException e) {
            return Map.of();
        }
    }

    private static byte[] aad(String namespace) {
        return ("ikanos upstream credentials\n" + namespace).getBytes(StandardCharsets.UTF_8);
    }
}
