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
package io.ikanos.spec.util;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Single place where Ikanos creates its Jackson 3 mappers.
 *
 * <p>Jackson 3 changed several defaults compared to Jackson 2: properties are sorted
 * alphabetically, unknown properties no longer fail deserialization, final fields and getters
 * are no longer used as mutators (which the spec model relies on for its {@code final} collection
 * fields), and {@code null} for primitives or trailing tokens now fail. Every mapper built here
 * starts from {@code configureForJackson2()} so that parsing and serialization keep the behavior
 * Ikanos had on Jackson 2. Callers then only state their own deviations, such as
 * {@link #lenientYaml()}.</p>
 */
public final class IkanosMappers {

    private IkanosMappers() {
    }

    /**
     * @return a JSON mapper builder preconfigured with the Jackson 2 defaults
     */
    public static JsonMapper.Builder jsonBuilder() {
        return JsonMapper.builder().configureForJackson2();
    }

    /**
     * @return a YAML mapper builder preconfigured with the Jackson 2 defaults
     */
    public static YAMLMapper.Builder yamlBuilder() {
        return YAMLMapper.builder().configureForJackson2();
    }

    /**
     * @return a new JSON mapper with the Jackson 2 defaults (unknown properties fail)
     */
    public static JsonMapper json() {
        return jsonBuilder().build();
    }

    /**
     * @return a new YAML mapper with the Jackson 2 defaults (unknown properties fail)
     */
    public static YAMLMapper yaml() {
        return yamlBuilder().build();
    }

    /**
     * @return a new YAML mapper with the Jackson 2 defaults that ignores unknown properties, as
     *         used to load capability documents
     */
    public static YAMLMapper lenientYaml() {
        return yamlBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /**
     * @return a new JSON mapper with the Jackson 2 defaults that ignores unknown properties
     */
    public static JsonMapper lenientJson() {
        return jsonBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }
}
