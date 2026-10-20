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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lets a warning through at most once per {@link #INTERVAL_MILLIS} and counts the ones held back,
 * so callers that trigger it cannot flood the log (issue #483).
 */
final class RateLimitedWarning {

    static final long INTERVAL_MILLIS = 60_000;

    private final LongSupplier clock;
    // Starts at the lowest value (nanoTime can be negative), so the first warning is always logged.
    private final AtomicLong nextWarningAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();

    RateLimitedWarning() {
        // Monotonic: a wall-clock step backwards must not silence the warning for longer.
        this(() -> System.nanoTime() / 1_000_000);
    }

    RateLimitedWarning(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * @return how many warnings were held back since the last one let through, or {@code -1} when
     *         this one must be held back too (it is then counted).
     */
    long acquire() {
        if (shouldWarn(clock.getAsLong())) {
            return suppressed.getAndSet(0);
        }
        suppressed.incrementAndGet();
        return -1;
    }

    /**
     * @return {@code true} when no warning was let through in the {@link #INTERVAL_MILLIS} before
     *         {@code now}, and reserves the next interval.
     */
    boolean shouldWarn(long now) {
        long next = nextWarningAt.get();
        return now >= next && nextWarningAt.compareAndSet(next, now + INTERVAL_MILLIS);
    }

    /** @return warnings held back since the last one let through. */
    long suppressed() {
        return suppressed.get();
    }
}
