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

/**
 * Turns the opaque values of an execution (inputs, results, decisions, updates) into bytes for a
 * store, and back. Ikanos implements it with its own JSON setup, so the engine needs no JSON
 * library; compression and encryption are the codec's concern.
 */
public interface PayloadCodec {

    /** Encodes a value. */
    byte[] encode(Object value);

    /** Decodes bytes produced by {@link #encode}. */
    Object decode(byte[] bytes);
}
