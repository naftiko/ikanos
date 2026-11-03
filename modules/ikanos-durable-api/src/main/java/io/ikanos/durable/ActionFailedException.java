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

/** Thrown by an {@link ActionExecutor} to end an attempt with a classified {@link Failure}. */
public class ActionFailedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Failure failure;

    public ActionFailedException(Failure failure) {
        this(failure, null);
    }

    public ActionFailedException(Failure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").message(), cause);
        this.failure = failure;
    }

    /** The classified failure to record. */
    public Failure failure() {
        return failure;
    }
}
