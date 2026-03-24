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
package io.naftiko.spec;

/**
 * Binding Specification Element.
 * Declares that the capability binds to an external source of variables.
 * Variables declared via 'keys' are injected using mustache-style expressions.
 */
public class BindingSpec {

    private volatile String namespace;

    private volatile String description;

    private volatile String location;

    private volatile BindingKeysSpec keys;

    public BindingSpec() {
    }

    public BindingSpec(String namespace, String description, String location, BindingKeysSpec keys) {
        this.namespace = namespace;
        this.description = description;
        this.location = location;
        this.keys = keys;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public BindingKeysSpec getKeys() {
        return keys;
    }

    public void setKeys(BindingKeysSpec keys) {
        this.keys = keys;
    }

}
