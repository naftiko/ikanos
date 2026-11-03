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

import tools.jackson.databind.ObjectMapper;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The engine's {@link PayloadCodec}: values are journaled as JSON with Ikanos's Jackson 3 setup,
 * and read back as JSON trees. The durable engine itself has no JSON dependency.
 */
public final class JsonPayloadCodec implements PayloadCodec {

    private final ObjectMapper mapper = IkanosMappers.json();

    @Override
    public byte[] encode(Object value) {
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public Object decode(byte[] bytes) {
        return mapper.readTree(bytes);
    }
}
