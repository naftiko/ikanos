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
package io.ikanos.durable;

import java.util.Objects;

/**
 * A classified failure, journaled with the action or execution it ends.
 *
 * <p>The class lets handlers match a failure without parsing its message: for example
 * {@code http.status.503}, {@code timeout} or {@code criteria}. The engine defines the classes it
 * raises itself as constants; every other class comes from the {@link ActionExecutor}.</p>
 *
 * @param errorClass a short, stable classification
 * @param message a human-readable description, never containing secrets
 */
public record Failure(String errorClass, String message) {

    /** The execution ran past its deadline. */
    public static final String DEADLINE_EXCEEDED = "deadline-exceeded";

    /** The action threw something other than an {@link ActionFailedException}. */
    public static final String UNEXPECTED = "unexpected";

    /** The thread running the action was interrupted. */
    public static final String INTERRUPTED = "interrupted";

    public Failure {
        Objects.requireNonNull(errorClass, "errorClass");
        if (errorClass.isBlank()) {
            throw new IllegalArgumentException("A failure class must not be blank");
        }
        message = message == null ? "" : message;
    }
}
