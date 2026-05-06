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
package io.naftiko.spec.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Binding Specification Element.
 *
 * <p>Declares that the capability binds to an external source of variables.
 * Variables declared via {@code keys} are injected using mustache-style expressions.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}. This satisfies SonarQube rule
 * {@code java:S3077}.
 */
public class BindingSpec {

    private final AtomicReference<String> namespace = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> location = new AtomicReference<>();
    private final AtomicReference<BindingKeysSpec> keys = new AtomicReference<>();

    public BindingSpec() {
    }

    public BindingSpec(String namespace, String description, String location, BindingKeysSpec keys) {
        this.namespace.set(namespace);
        this.description.set(description);
        this.location.set(location);
        this.keys.set(keys);
    }

    public String getNamespace() {
        return namespace.get();
    }

    public void setNamespace(String namespace) {
        this.namespace.set(namespace);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    public String getLocation() {
        return location.get();
    }

    public void setLocation(String location) {
        this.location.set(location);
    }

    public BindingKeysSpec getKeys() {
        return keys.get();
    }

    public void setKeys(BindingKeysSpec keys) {
        this.keys.set(keys);
    }

}
