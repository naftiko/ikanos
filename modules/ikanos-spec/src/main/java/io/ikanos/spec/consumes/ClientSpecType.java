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
package io.ikanos.spec.consumes;

/**
 * Service-provider interface binding a {@code consumes} {@code type} value to its spec class.
 *
 * <p>The built-in {@code http} type is registered statically by {@link ClientSpecTypes}. Optional
 * adapters ship their own implementation, listed in
 * {@code META-INF/services/io.ikanos.spec.consumes.ClientSpecType}, and are discovered with
 * {@link java.util.ServiceLoader}.</p>
 *
 * <p>The spec class must extend {@link ClientSpec} and be annotated
 * {@code @JsonDeserialize(using = JsonDeserializer.None.class)}; otherwise Jackson re-enters
 * {@link ClientSpecDeserializer} and recurses.</p>
 */
public interface ClientSpecType {

    /** @return the {@code type} value, e.g. {@code "mcp"}; never {@code null} */
    String type();

    /** @return the spec class to deserialize entries of this type into */
    Class<? extends ClientSpec> specClass();
}
