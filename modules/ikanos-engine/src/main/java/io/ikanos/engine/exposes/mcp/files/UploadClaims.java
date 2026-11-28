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
package io.ikanos.engine.exposes.mcp.files;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The record of the {@code requestState} values that already ran a tool that is unsafe to run
 * twice (files extension, Repeated Retries): each one is accepted at most once, until it expires.
 *
 * <p>It is kept in memory, so it holds for one process: the engine has no store shared by
 * replicas yet. Replicas behind one address need the same {@code requestState} to reach the same
 * replica, or a shared implementation of this class.</p>
 */
public class UploadClaims {

    /** How many unexpired claims are kept at most. */
    static final int MAX_CLAIMS = 100_000;

    private final Map<String, Instant> claims = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxClaims;

    public UploadClaims(Clock clock) {
        this(clock, MAX_CLAIMS);
    }

    UploadClaims(Clock clock, int maxClaims) {
        this.clock = clock;
        this.maxClaims = maxClaims;
    }

    /**
     * Claims {@code id}, atomically.
     *
     * @param expiresAt when the {@code requestState} it names expires: the claim is kept until
     *        then
     * @return {@code true} the first time, {@code false} when it was already claimed
     * @throws IllegalStateException when the record is full of unexpired claims; the call is
     *         then refused rather than risk running a tool twice
     */
    public boolean claim(String id, Instant expiresAt) {
        if (claims.size() >= maxClaims) {
            dropExpired();
            if (claims.size() >= maxClaims) {
                throw new IllegalStateException("Too many pending upload retries");
            }
        }
        return claims.putIfAbsent(id, expiresAt) == null;
    }

    /** Drops the claims whose {@code requestState} has expired: it can no longer be presented. */
    void dropExpired() {
        Instant now = clock.instant();
        for (Iterator<Map.Entry<String, Instant>> it = claims.entrySet().iterator();
                it.hasNext();) {
            if (it.next().getValue().isBefore(now)) {
                it.remove();
            }
        }
    }

    /** How many claims are kept. */
    int size() {
        return claims.size();
    }
}
