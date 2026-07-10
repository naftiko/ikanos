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

/**
 * Provides runtime variables for external reference resolution.
 * Implementations can source variables from environment variables, secrets managers,
 * or other context stores.
 */
public interface ExecutionContext {

    /**
     * Retrieves a variable value from the execution context.
     * 
     * @param key The variable key to look up
     * @return The variable value, or null if not found
     */
    String getVariable(String key);

}
