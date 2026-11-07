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
package io.ikanos.durable;

import java.util.List;
import java.util.Objects;

/** How a run of an execution ended, as far as its caller can see. */
public sealed interface ExecutionResult {

    /** The execution completed with a result. */
    record Completed(Object result) implements ExecutionResult {
    }

    /** The execution failed. */
    record Failed(Failure failure) implements ExecutionResult {

        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * The execution waits for updates it cannot get by itself, such as the caller's answers to
     * input requests. The exposed adapter projects them (an MCP {@code input_required} result, a
     * REST {@code 202}).
     */
    record Suspended(List<Command.AwaitUpdate> awaiting) implements ExecutionResult {

        public Suspended {
            awaiting = List.copyOf(awaiting);
            if (awaiting.isEmpty()) {
                throw new IllegalArgumentException("A suspended execution awaits at least one update");
            }
        }
    }
}
