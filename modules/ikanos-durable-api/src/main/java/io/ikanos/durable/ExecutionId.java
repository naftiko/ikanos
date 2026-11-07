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
import java.util.UUID;

/**
 * Identity of one execution: one governed call, from its start to its completion, failure or
 * cancellation. It names the journal of that execution and is the first half of every action's
 * idempotency key.
 *
 * @param value an opaque, non-blank identifier
 */
public record ExecutionId(String value) {

    public ExecutionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An execution ID must not be blank");
        }
    }

    /** Returns a new, random execution ID. */
    public static ExecutionId random() {
        return new ExecutionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
