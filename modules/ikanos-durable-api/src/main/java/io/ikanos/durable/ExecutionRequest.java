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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A request to run an execution.
 *
 * @param executionId the execution's identity
 * @param input the execution's input, opaque to the engine
 * @param updates updates known before the run starts, keyed by the path that awaits them. Without
 *        a journal, this is how a caller's answers come back: a stateless multi round-trip request
 *        replays the execution from the start with the answers collected so far.
 * @param deadline the instant after which the execution fails, or {@code null} for none
 */
public record ExecutionRequest(ExecutionId executionId, Object input, Map<NodePath, Object> updates,
        Instant deadline) {

    public ExecutionRequest {
        Objects.requireNonNull(executionId, "executionId");
        updates = updates == null ? Map.of() : Map.copyOf(updates);
    }

    /** A request with no known update and no deadline. */
    public static ExecutionRequest of(ExecutionId executionId, Object input) {
        return new ExecutionRequest(executionId, input, Map.of(), null);
    }
}
