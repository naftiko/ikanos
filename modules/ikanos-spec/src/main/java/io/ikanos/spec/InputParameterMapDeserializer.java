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

import io.ikanos.spec.util.NamedMapDeserializer;

/**
 * Jackson deserializer for {@code inputParameters} named-object maps.
 * Reads {@code { "param-name": { type: ..., in: ..., ... }, ... }} and injects the key as
 * {@link InputParameterSpec#setName(String)}.
 *
 * <p>Each value is further deserialized by {@link InputParameterDeserializer} (set via
 * {@code @JsonDeserialize} on {@link InputParameterSpec}), which handles the variant
 * shape (e.g. {@code type}/{@code in} resolution).</p>
 *
 * <p>Supports backward-compatibility with the legacy {@code [{name: ..., ...}, ...]} array
 * form via the base {@link NamedMapDeserializer}.</p>
 */
public class InputParameterMapDeserializer extends NamedMapDeserializer<InputParameterSpec> {

    public InputParameterMapDeserializer() {
        super(InputParameterSpec.class, InputParameterSpec::setName);
    }
}
