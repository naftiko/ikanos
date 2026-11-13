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
package io.ikanos.spec.consumes.sql;

import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Connection pool bounds of a {@code consumes: type: sql} entry. Every field is optional; an
 * absent field keeps the pool's default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SqlPoolSpec {

    private final AtomicReference<Integer> maxSize = new AtomicReference<>();
    private final AtomicReference<Integer> minIdle = new AtomicReference<>();
    private final AtomicReference<Long> connectionTimeoutMs = new AtomicReference<>();
    private final AtomicReference<Long> maxLifetimeMs = new AtomicReference<>();

    /** @return the maximum number of connections, or {@code null} for the default (10) */
    public Integer getMaxSize() {
        return maxSize.get();
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize.set(maxSize);
    }

    /** @return the minimum number of idle connections, or {@code null} for the default */
    public Integer getMinIdle() {
        return minIdle.get();
    }

    public void setMinIdle(Integer minIdle) {
        this.minIdle.set(minIdle);
    }

    /** @return how long a call waits for a free connection, or {@code null} for the default */
    public Long getConnectionTimeoutMs() {
        return connectionTimeoutMs.get();
    }

    public void setConnectionTimeoutMs(Long connectionTimeoutMs) {
        this.connectionTimeoutMs.set(connectionTimeoutMs);
    }

    /** @return the maximum lifetime of a pooled connection, or {@code null} for the default */
    public Long getMaxLifetimeMs() {
        return maxLifetimeMs.get();
    }

    public void setMaxLifetimeMs(Long maxLifetimeMs) {
        this.maxLifetimeMs.set(maxLifetimeMs);
    }
}
