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
package io.ikanos.engine.execution;

import java.time.Clock;
import java.util.Map;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The durability mode of a capability, resolved at load time from the control port's
 * {@code durability} block.
 *
 * <p>{@code none} needs nothing. {@code memory} opens the {@code memory} store, discovered with
 * {@code ServiceLoader}; when its module is missing, the capability fails at load with an error
 * naming the module, and never falls back to {@code none}. {@code local} needs the H2 store module,
 * which a later increment ships, so it fails at load for now.</p>
 */
public final class Durability {

    /**
     * Digest placeholder until executions are pinned to the digest of their compiled tree. In the
     * {@code memory} mode no execution outlives the spec it started with.
     */
    static final String UNPINNED = "unpinned";

    private static final Durability NONE = new Durability(DurabilitySpec.MODE_NONE, null, null);

    private final String mode;
    private final JournaledRunner runner;
    private final Long retentionMs;

    private Durability(String mode, JournaledRunner runner, Long retentionMs) {
        this.mode = mode;
        this.runner = runner;
        this.retentionMs = retentionMs;
    }

    /** The {@code none} mode. */
    public static Durability none() {
        return NONE;
    }

    /**
     * Resolves the durability declared on the control port; {@code null} means {@code none}.
     *
     * @throws IllegalStateException when the mode's store module is missing or not available yet
     * @throws IllegalArgumentException for an unknown mode or a store type the mode cannot use
     */
    public static Durability fromSpec(DurabilitySpec spec) {
        String mode = spec == null ? DurabilitySpec.MODE_NONE : spec.effectiveMode();
        switch (mode) {
            case DurabilitySpec.MODE_NONE:
                return NONE;
            case DurabilitySpec.MODE_MEMORY: {
                String type = spec.getStore() != null && spec.getStore().getType() != null
                        ? spec.getStore().getType() : DurabilitySpec.MODE_MEMORY;
                if (!DurabilitySpec.MODE_MEMORY.equals(type)) {
                    throw new IllegalArgumentException("Durability mode 'memory' keeps executions "
                            + "in process memory; it cannot use store type '" + type + "'");
                }
                StoreProvider provider = StoreProvider.find(type)
                        .orElseThrow(() -> new IllegalStateException("Durability mode 'memory' "
                                + "needs the store module io.ikanos:ikanos-durable-store-memory on "
                                + "the class path; available store types: "
                                + StoreProvider.available()));
                Stores stores = provider.open(Map.of());
                return new Durability(mode, new JournaledRunner(stores, new JsonPayloadCodec(),
                        Clock.systemUTC(), UNPINNED), retentionMs(spec.getRetention()));
            }
            case DurabilitySpec.MODE_LOCAL:
                throw new IllegalStateException("Durability mode 'local' is not available "
                        + "in this release: its H2 store module ships with a later increment. Use "
                        + "'memory' or 'none'.");
            default:
                throw new IllegalArgumentException("Unknown durability mode '" + mode
                        + "'; expected none, memory or local");
        }
    }

    static Long retentionMs(String retention) {
        if (retention == null || retention.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(retention).toMillis();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("durability.retention must be an ISO 8601 "
                    + "duration such as P7D, not '" + retention + "'", e);
        }
    }

    /** How long a completed execution is kept, in milliseconds, or {@code null} if unbounded. */
    public Long retentionMs() {
        return retentionMs;
    }

    /** The resolved mode. */
    public String mode() {
        return mode;
    }

    /** Whether executions are journaled. */
    public boolean isDurable() {
        return runner != null;
    }

    /** The runner of the durable modes, or {@code null} under {@code none}. */
    public JournaledRunner runner() {
        return runner;
    }
}
