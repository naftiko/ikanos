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
package io.ikanos.durable.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * A store module, discovered with {@link ServiceLoader} by its {@link #type()}: the value of
 * {@code durability.store.type} in a capability, or the store implied by its mode.
 *
 * <p>Store modules are optional: a capability whose store module is missing fails at load with an
 * error naming the module, and never falls back to running without a journal.</p>
 */
public interface StoreProvider {

    /** The store type this provider serves, such as {@code memory} or {@code h2}. */
    String type();

    /** The Maven artifact that provides this store, for error messages. */
    String module();

    /** Opens the stores, configured by {@code settings}. */
    Stores open(Map<String, String> settings);

    /** Finds the provider of {@code type} on the class path. */
    static Optional<StoreProvider> find(String type) {
        return ServiceLoader.load(StoreProvider.class).stream().map(ServiceLoader.Provider::get)
                .filter(provider -> provider.type().equals(type)).findFirst();
    }

    /** The store types available on the class path. */
    static List<String> available() {
        return ServiceLoader.load(StoreProvider.class).stream()
                .map(provider -> provider.get().type()).sorted().toList();
    }
}
