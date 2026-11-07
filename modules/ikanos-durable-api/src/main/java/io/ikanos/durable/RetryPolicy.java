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

import java.time.Duration;
import java.util.Objects;

/**
 * Durable retry policy of an action: the attempts the engine makes across restarts and replicas,
 * each after a journaled timer. It is the outer of the two retry layers; retries inside one
 * attempt belong to the caller's in-process resolution and are invisible to the engine.
 *
 * @param maxAttempts total attempts, at least 1
 * @param initialBackoff delay before the second attempt
 * @param multiplier growth of the delay between attempts, at least 1
 * @param maxBackoff upper bound of any delay
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier,
        Duration maxBackoff) {

    /** One attempt, no retry. */
    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);

    public RetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("Backoff durations must not be negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1: " + multiplier);
        }
    }

    /**
     * Returns the delay before attempt {@code attempt} (2 for the first retry), capped by
     * {@link #maxBackoff()}.
     */
    public Duration backoffBefore(int attempt) {
        if (attempt < 2) {
            return Duration.ZERO;
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 2.0);
        long capped = (long) Math.min(millis, maxBackoff.toMillis());
        return Duration.ofMillis(capped);
    }
}
