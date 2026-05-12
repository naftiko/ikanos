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
package io.ikanos.spec.aggregates;

import io.ikanos.spec.util.NamedMapDeserializer;

/**
 * Jackson deserializer for {@code capability.aggregates} named-object map.
 * Reads {@code { "namespace": { ... }, ... }} and injects the key as
 * {@link AggregateSpec#setNamespace(String)}.
 */
public class AggregateMapDeserializer extends NamedMapDeserializer<AggregateSpec> {

    public AggregateMapDeserializer() {
        super(AggregateSpec.class, AggregateSpec::setNamespace);
    }
}
