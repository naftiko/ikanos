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
package io.ikanos.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Shared definitions of an MCP adapter or an aggregate (#910).
 *
 * <p>A {@code definitions} block declares named input parameter sets and named schemas that the
 * tools (or flows) of the same adapter (or aggregate) reuse, through {@code inputParameterSets:
 * [names]} and {@code schema: name}. Definitions are visible to their own namespace only, and
 * are expanded at load time, before {@code ref} resolution, so the engine and the MCP clients
 * only ever see the inline equivalent.</p>
 *
 * <ul>
 *   <li>{@code inputParameterSets}: set name to a map of input parameters, each declared like a
 *       tool input ({@code McpToolInputParameter}).</li>
 *   <li>{@code schemas}: schema name to a schema declared like a nested tool input schema
 *       ({@code McpToolInputSchema}), where {@code required} is the list of required
 *       properties.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Both maps are held in {@link AtomicReference}s as synchronized {@link LinkedHashMap}s that keep
 * the declaration order. This satisfies SonarQube rule {@code java:S3077}.
 */
public class DefinitionsSpec {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = InputParameterSetsDeserializer.class)
    private final AtomicReference<Map<String, Map<String, InputParameterSpec>>> inputParameterSets =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = InputParameterMapDeserializer.class)
    private final AtomicReference<Map<String, InputParameterSpec>> schemas =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    /** @return the input parameter sets, keyed by set name, then by parameter name */
    public Map<String, Map<String, InputParameterSpec>> getInputParameterSets() {
        return inputParameterSets.get();
    }

    public void setInputParameterSets(Map<String, Map<String, InputParameterSpec>> sets) {
        inputParameterSets.set(Collections.synchronizedMap(
                new LinkedHashMap<>(sets != null ? sets : Map.of())));
    }

    /** @return the schemas, keyed by schema name */
    public Map<String, InputParameterSpec> getSchemas() {
        return schemas.get();
    }

    public void setSchemas(Map<String, InputParameterSpec> schemas) {
        this.schemas.set(Collections.synchronizedMap(
                new LinkedHashMap<>(schemas != null ? schemas : Map.of())));
    }
}
