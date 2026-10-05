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
package io.ikanos.engine.consumes;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Protocol-neutral result of one consumed invocation.
 *
 * <p>Every reader in the engine (step orchestration, aggregate flows, the MCP tool and resource
 * handlers, the REST resource restlet) consumes this interface instead of a protocol-specific type.
 * Each client adapter keeps its native body form and converts lazily:</p>
 *
 * <ul>
 *   <li>{@link #text()} reads the body once and memoizes it, so it can be read again;</li>
 *   <li>{@link #document()} returns the body as a JSON tree. Adapters whose native result is
 *       already a tree (MCP {@code structuredContent}, SQL rows, an in-process flow) return it
 *       directly; the HTTP adapter converts using the operation's {@code outputRawFormat};</li>
 *   <li>{@link #bytes(long)} returns the body as bounded raw bytes for the binary-content path.
 *       Reading bytes and reading text from the same streamed body are mutually exclusive.</li>
 * </ul>
 *
 * <p>Implementations are used by a single request thread and need not be thread-safe.</p>
 */
public interface ConsumedResult {

    /** @return the protocol-neutral outcome; never {@code null} */
    Outcome outcome();

    /**
     * HTTP-equivalent status code. Non-HTTP adapters map their native outcome to the nearest HTTP
     * code at the source, so the REST exposer can put it on the wire without a translation table.
     *
     * @return the status code
     */
    int status();

    /** @return a human-readable reason for {@link #status()}, or {@code null} */
    String reason();

    /**
     * Strict success test: {@code true} only when {@link #status()} is 2xx (200-299).
     *
     * <p>Unlike {@link Outcome#SUCCESS}, which means "not an error", a 3xx and a missing status
     * ({@code 0}) are not successful. Step orchestration uses this test so a call step only
     * feeds later steps when the upstream answered 2xx (#739).</p>
     *
     * @return whether the status is in the 2xx range
     */
    default boolean isSuccessful() {
        int status = status();
        return status >= 200 && status < 300;
    }

    /** @return {@code true} when the result carries a non-empty body */
    boolean hasBody();

    /**
     * Effective contract media type of the body. For a binary operation this applies the
     * binary-content precedence (declared {@code outputMediaType}, then a specific upstream type,
     * then the upstream type, then {@code application/octet-stream}).
     *
     * @return the media type, or {@code null} when unknown
     */
    String mediaType();

    /**
     * The body as text, read once and memoized.
     *
     * @return the text, or {@code null} when there is no body
     * @throws IOException if the body cannot be read
     */
    String text() throws IOException;

    /**
     * The body as a JSON tree.
     *
     * @return the tree, or {@code null} when the body is absent, empty, or binary
     * @throws IOException if the body cannot be read or converted
     */
    JsonNode document() throws IOException;

    /**
     * The body as raw bytes, enforcing a hard size cap while reading. Idempotent.
     *
     * @param maxBytes the cap in bytes
     * @return the bytes, or {@code null} when there is no body
     * @throws io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException if the body
     *         exceeds {@code maxBytes}
     * @throws IOException if the body cannot be read
     */
    byte[] bytes(long maxBytes) throws IOException;

    /** @return the output declarations of the operation that produced this result */
    ConsumedOperationView operation();
}
