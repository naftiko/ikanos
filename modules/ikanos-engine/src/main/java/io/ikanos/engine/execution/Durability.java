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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.exposes.control.DurabilityStoreSpec;
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The durability mode of a capability, resolved at load time from the control port's
 * {@code durability} block.
 *
 * <p>{@code none} needs nothing. {@code memory} opens the {@code memory} store, {@code local}
 * the {@code h2} store at {@code store.path}, and {@code distributed} the {@code postgres} store
 * at {@code store.url}, which several replicas share; each is discovered with
 * {@code ServiceLoader}. When the store module is missing, the capability fails at load with an
 * error naming the module, and never falls back to {@code none}. {@code distributed} also fails
 * at load on a store that does not declare itself shared, or without a {@code sealingKey}.
 * {@link #close()} releases the store, such as the H2 file or the PostgreSQL connection pool.</p>
 *
 * <p>An operator that runs the capability, such as Skipper on Kubernetes, declares the store and
 * the replica count it runs it with ({@link Placement}); {@link #checkPlacement} refuses at load a
 * durability that disagrees with them, before any store opens.</p>
 */
public final class Durability implements AutoCloseable {

    /**
     * Digest of a durability resolved without a spec, such as in tests. A loaded capability pins
     * its executions to the digest of its spec instead ({@link #fromSpec(DurabilitySpec, String)}).
     */
    static final String UNPINNED = "unpinned";

    /** The store type of the {@code local} mode. */
    static final String H2 = "h2";

    /** The store type of the {@code distributed} mode. */
    static final String POSTGRES = "postgres";

    /** The store type the operator runs the capability with, such as {@code h2}. */
    public static final String STORE_VARIABLE = "IKANOS_DURABILITY_STORE";

    /** The number of replicas the operator runs the capability as. */
    public static final String REPLICAS_VARIABLE = "IKANOS_REPLICAS";

    /** The store types an operator can declare. */
    private static final Set<String> OPERATOR_STORES = Set.of(H2, POSTGRES, "temporal");

    /** The module providing the {@code h2} and {@code postgres} stores. */
    private static final String JDBC_MODULE = "io.ikanos:ikanos-durable-store-jdbc";

    private static final Durability NONE =
            new Durability(DurabilitySpec.MODE_NONE, null, null, null);

    private final String mode;
    private final JournaledRunner runner;
    private final Long retentionMs;
    private final Stores stores;

    private Durability(String mode, JournaledRunner runner, Long retentionMs, Stores stores) {
        this.mode = mode;
        this.runner = runner;
        this.retentionMs = retentionMs;
        this.stores = stores;
    }

    /** The {@code none} mode. */
    public static Durability none() {
        return NONE;
    }

    /**
     * Resolves the durability declared on the control port; {@code null} means {@code none}.
     *
     * @throws IllegalStateException when the mode's store module is missing, or its store cannot
     *         be opened
     * @throws IllegalArgumentException for an unknown mode, a store type the mode cannot use, or
     *         a {@code local} mode without {@code store.path}
     */
    public static Durability fromSpec(DurabilitySpec spec) {
        return fromSpec(spec, UNPINNED);
    }

    /**
     * Resolves the durability declared on the control port, pinning its executions to
     * {@code specDigest}: a restarted process fails an unfinished execution recorded under another
     * digest rather than replay it into steps that may have changed.
     *
     * @see #fromSpec(DurabilitySpec)
     */
    public static Durability fromSpec(DurabilitySpec spec, String specDigest) {
        return fromSpec(spec, specDigest, Map.of());
    }

    /**
     * Resolves the durability declared on the control port, with the store's connection
     * settings ({@code url}, {@code user}, {@code password}) resolved against {@code bindings}.
     *
     * @throws IllegalArgumentException also for a {@code distributed} mode without
     *         {@code store.url}, with a setting that does not resolve, or on a store that several
     *         replicas cannot share
     * @see #fromSpec(DurabilitySpec, String)
     */
    public static Durability fromSpec(DurabilitySpec spec, String specDigest,
            Map<String, Object> bindings) {
        String mode = spec == null ? DurabilitySpec.MODE_NONE : spec.effectiveMode();
        switch (mode) {
            case DurabilitySpec.MODE_NONE:
                return NONE;
            case DurabilitySpec.MODE_MEMORY: {
                String type = storeType(spec, DurabilitySpec.MODE_MEMORY);
                if (!DurabilitySpec.MODE_MEMORY.equals(type)) {
                    throw new IllegalArgumentException("Durability mode 'memory' keeps executions "
                            + "in process memory; it cannot use store type '" + type + "'");
                }
                return open(mode, type, "io.ikanos:ikanos-durable-store-memory", Map.of(),
                        retentionMs(spec.getRetention()), specDigest);
            }
            case DurabilitySpec.MODE_LOCAL: {
                String type = storeType(spec, H2);
                if (!H2.equals(type)) {
                    throw new IllegalArgumentException("Durability mode 'local' keeps executions "
                            + "in an embedded H2 file; it cannot use store type '" + type + "'");
                }
                String path = spec.getStore() != null ? spec.getStore().getPath() : null;
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("Durability mode 'local' needs "
                            + "durability.store.path: the file its executions are kept in");
                }
                return open(mode, type, JDBC_MODULE, Map.of("path", path),
                        retentionMs(spec.getRetention()), specDigest);
            }
            case DurabilitySpec.MODE_DISTRIBUTED:
                return distributed(spec, specDigest, bindings);
            default:
                throw new IllegalArgumentException("Unknown durability mode '" + mode
                        + "'; expected none, memory, local or distributed");
        }
    }

    private static Durability distributed(DurabilitySpec spec, String specDigest,
            Map<String, Object> bindings) {
        String type = storeType(spec, POSTGRES);
        if (H2.equals(type) || DurabilitySpec.MODE_MEMORY.equals(type)) {
            throw notShared(type);
        }
        DurabilityStoreSpec store = spec.getStore();
        String url = setting(store == null ? null : store.getUrl(), "url", bindings);
        if (url == null) {
            throw new IllegalArgumentException("Durability mode 'distributed' needs "
                    + "durability.store.url: the JDBC URL of the database its replicas share");
        }
        if (spec.getSealingKey() == null || spec.getSealingKey().isBlank()) {
            // Without it, each replica draws its own key and rejects the state another sealed,
            // such as a requestState or an upstream task's credentials.
            throw new IllegalArgumentException("Durability mode 'distributed' needs "
                    + "durability.sealingKey: one key every replica shares");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("url", url);
        String user = setting(store.getUser(), "user", bindings);
        if (user != null) {
            settings.put("user", user);
        }
        String password = setting(store.getPassword(), "password", bindings);
        if (password != null) {
            settings.put("password", password);
        }
        Durability durability = open(DurabilitySpec.MODE_DISTRIBUTED, type,
                POSTGRES.equals(type) ? JDBC_MODULE : "the module of store type '" + type + "'",
                settings, retentionMs(spec.getRetention()), specDigest);
        if (!durability.runner.isShared()) {
            durability.close();
            throw notShared(type);
        }
        return durability;
    }

    /**
     * How an operator runs the capability: the store it gave it and its replica count, each
     * {@code null} when the operator declares none.
     *
     * @param store the store type, such as {@code h2} or {@code postgres}, or {@code null}
     * @param replicas the replica count, at least 1, or {@code null}
     */
    public record Placement(String store, Integer replicas) {

        /** No operator: nothing is checked, as under Docker or a plain {@code ikanos serve}. */
        public static final Placement NONE = new Placement(null, null);

        /**
         * Reads {@value Durability#STORE_VARIABLE} and {@value Durability#REPLICAS_VARIABLE}.
         *
         * @throws IllegalArgumentException when a variable is set to a value that does not parse
         */
        public static Placement fromEnvironment(Function<String, String> environment) {
            String store = blankToNull(environment.apply(STORE_VARIABLE));
            if (store != null && !OPERATOR_STORES.contains(store)) {
                throw new IllegalArgumentException(STORE_VARIABLE + " must be h2, postgres or "
                        + "temporal, not '" + store + "'");
            }
            String declared = blankToNull(environment.apply(REPLICAS_VARIABLE));
            Integer replicas = null;
            if (declared != null) {
                try {
                    replicas = Integer.valueOf(declared);
                } catch (NumberFormatException e) {
                    replicas = 0;
                }
                if (replicas < 1) {
                    throw new IllegalArgumentException(REPLICAS_VARIABLE + " must be a whole "
                            + "number of at least 1, not '" + declared + "'");
                }
            }
            return new Placement(store, replicas);
        }

        /** Whether an operator declared how it runs the capability. */
        boolean isManaged() {
            return store != null || replicas != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * Refuses a durability that disagrees with how the operator runs the capability: a mode
     * that keeps executions in one process ({@code memory}, {@code local}) on more than one
     * replica, or a store type other than the one the operator gave it. Nothing is checked when
     * no operator declares a placement. An unknown mode is left to {@link #fromSpec}.
     *
     * @throws IllegalArgumentException on a disagreement, naming the operator's field to change
     */
    public static void checkPlacement(DurabilitySpec spec, Placement placement) {
        if (placement == null || !placement.isManaged()) {
            return;
        }
        String mode = spec == null ? DurabilitySpec.MODE_NONE : spec.effectiveMode();
        String store;
        switch (mode) {
            case DurabilitySpec.MODE_NONE:
                store = null;
                break;
            case DurabilitySpec.MODE_MEMORY:
                if (!DurabilitySpec.MODE_MEMORY.equals(
                        storeType(spec, DurabilitySpec.MODE_MEMORY))) {
                    return;
                }
                // The operator gives this mode no store: its executions live in the process.
                store = null;
                break;
            case DurabilitySpec.MODE_LOCAL:
                store = storeType(spec, H2);
                if (!H2.equals(store)) {
                    return;
                }
                break;
            case DurabilitySpec.MODE_DISTRIBUTED:
                store = storeType(spec, POSTGRES);
                if (H2.equals(store) || DurabilitySpec.MODE_MEMORY.equals(store)) {
                    return;
                }
                break;
            default:
                // An unknown mode, or a store type the mode cannot use, is an error in the
                // capability itself, which fromSpec reports; the operator is not the one to fix.
                return;
        }
        boolean oneProcess = DurabilitySpec.MODE_MEMORY.equals(mode)
                || DurabilitySpec.MODE_LOCAL.equals(mode);
        if (oneProcess && placement.replicas() != null && placement.replicas() > 1) {
            throw new IllegalArgumentException("Durability mode '" + mode + "' keeps executions "
                    + "in one process; it cannot run as " + placement.replicas() + " replicas ("
                    + REPLICAS_VARIABLE + "). Use 'distributed', or set "
                    + "spec.durability.replicas to 1.");
        }
        if (store == null && placement.store() != null) {
            throw new IllegalArgumentException("Durability mode '" + mode + "' uses no store, "
                    + "but the operator runs this capability with store '" + placement.store()
                    + "' (" + STORE_VARIABLE + "). Declare durability mode '"
                    + modeOf(placement.store()) + "' with store type '" + placement.store()
                    + "' on the control port, or remove spec.durability.store.");
        }
        if (store != null && !store.equals(placement.store())) {
            String fix = "Set spec.durability.store to '" + store + "'";
            if (placement.store() != null) {
                fix += ", or declare durability mode '" + modeOf(placement.store())
                        + "' with store type '" + placement.store() + "' on the control port";
            }
            throw new IllegalArgumentException("Durability mode '" + mode + "' keeps executions "
                    + "in store '" + store + "', but the operator runs this capability "
                    + (placement.store() == null ? "without a store"
                            : "with store '" + placement.store() + "'")
                    + " (" + STORE_VARIABLE + "). " + fix + ".");
        }
    }

    /** The durability mode a store type an operator declares serves. */
    private static String modeOf(String store) {
        return H2.equals(store) ? DurabilitySpec.MODE_LOCAL : DurabilitySpec.MODE_DISTRIBUTED;
    }

    private static IllegalArgumentException notShared(String type) {
        return new IllegalArgumentException("Durability mode 'distributed' needs a store "
                + "several replicas share; store type '" + type + "' is not one");
    }

    /**
     * A store setting resolved against the bindings, or {@code null} when it is not declared.
     *
     * @throws IllegalArgumentException when it names a binding that does not resolve
     */
    static String setting(String declared, String name, Map<String, Object> bindings) {
        if (declared == null || declared.isBlank()) {
            return null;
        }
        String resolved = declared.contains("{{")
                ? Resolver.resolveMustacheTemplate(declared, bindings) : declared;
        if (resolved == null || resolved.isBlank() || resolved.contains("{{")) {
            // Never quotes the value: it may be a secret.
            throw new IllegalArgumentException("durability.store." + name
                    + " does not resolve: declare its variable in capability.binds");
        }
        return resolved;
    }

    private static String storeType(DurabilitySpec spec, String defaultType) {
        return spec.getStore() != null && spec.getStore().getType() != null
                ? spec.getStore().getType() : defaultType;
    }

    private static Durability open(String mode, String type, String module,
            Map<String, String> settings, Long retentionMs, String specDigest) {
        StoreProvider provider = StoreProvider.find(type)
                .orElseThrow(() -> new IllegalStateException("Durability mode '" + mode
                        + "' needs the store module " + module + " on the class path; available "
                        + "store types: " + StoreProvider.available()));
        Stores stores = provider.open(settings);
        return new Durability(mode, new JournaledRunner(stores, new JsonPayloadCodec(),
                Clock.systemUTC(), specDigest), retentionMs, stores);
    }

    /** Releases the store, such as the H2 file of {@code local}; nothing under {@code none}. */
    @Override
    public void close() {
        if (runner != null) {
            runner.close();
        }
        if (stores != null) {
            stores.close();
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

    /** Whether several replicas share the store, as under {@code distributed}. */
    public boolean isShared() {
        return runner != null && runner.isShared();
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
