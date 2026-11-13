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
package io.ikanos.engine.consumes.sql;

/**
 * A SQL call that ran but produced something the adapter cannot return, such as a binary column.
 * Reported as an upstream error (502), never with row data or SQL text in its message.
 */
public class SqlClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SqlClientException(String message) {
        super(message);
    }
}
