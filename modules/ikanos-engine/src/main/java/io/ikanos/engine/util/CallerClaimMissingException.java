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
package io.ikanos.engine.util;

import java.util.List;

/**
 * A template reads a value of the caller's validated token ({@code {{auth.claims...}}}) that the
 * token does not carry, or carries in a form the engine refuses to pass on (#998). The call fails
 * rather than sending an empty or partial identity to the consumed API. The exposed adapters
 * answer it as a refusal of the caller's token, never as an upstream failure.
 *
 * <p>The message names the template references, such as {@code auth.claims.sub}, never a value.
 * It is an {@link IllegalArgumentException} so that every path that handles a bad call already
 * catches it; the adapters then find it in the cause chain ({@link #find}) to answer it.</p>
 */
public class CallerClaimMissingException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final List<String> references;

    public CallerClaimMissingException(List<String> references) {
        super("The access token does not carry " + String.join(", ", references)
                + ", which this call sends to a consumed API");
        this.references = List.copyOf(references);
    }

    /** @return the template references that had no usable value, such as {@code auth.claims.sub}. */
    public List<String> getReferences() {
        return references;
    }

    /**
     * @return the first {@link CallerClaimMissingException} in the cause chain of {@code error},
     *         or {@code null} when there is none
     */
    public static CallerClaimMissingException find(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CallerClaimMissingException missing) {
                return missing;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }
}
