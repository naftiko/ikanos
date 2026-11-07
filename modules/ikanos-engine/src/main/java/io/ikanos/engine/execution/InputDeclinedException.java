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

/**
 * The caller declined or cancelled an {@code elicit} step's request, which fails the step. The
 * decision came from the caller, not from an upstream, so an adapter can tell it apart: REST
 * answers {@code 409 Conflict}, MCP a tool error.
 */
public class InputDeclinedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public InputDeclinedException(String message) {
        super(message);
    }
}
