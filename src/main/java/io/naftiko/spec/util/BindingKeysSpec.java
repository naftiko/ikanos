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

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Binding Keys Specification Element.
 * Maps variable names (SCREAMING_SNAKE_CASE) to source keys in the provider.
 * Uses @JsonAnySetter to allow arbitrary key-value pairs in the YAML/JSON.
 */
public class BindingKeysSpec {

    private Map<String, String> keys;

    public BindingKeysSpec() {
        this.keys = new HashMap<>();
    }

    public BindingKeysSpec(Map<String, String> keys) {
        this.keys = keys != null ? new HashMap<>(keys) : new HashMap<>();
    }

    @JsonAnySetter
    public void setKey(String variableName, Object value) {
        if (value != null) {
            this.keys.put(variableName, value.toString());
        }
    }

    @JsonValue
    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys != null ? new HashMap<>(keys) : new HashMap<>();
    }

    public void putKey(String variableName, String contextKey) {
        this.keys.put(variableName, contextKey);
    }

    public String getKey(String variableName) {
        return this.keys.get(variableName);
    }

}
