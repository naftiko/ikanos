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

import java.util.Objects;

/**
 * One attempt of an action, handed to the {@link ActionExecutor}.
 *
 * @param executionId the execution the action belongs to
 * @param path the action's node path
 * @param action the opaque action descriptor from {@link Command.ScheduleAction}
 * @param attempt the attempt number, starting at 1
 */
public record ActionRequest(ExecutionId executionId, NodePath path, Object action, int attempt) {

    public ActionRequest {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(path, "path");
        if (attempt < 1) {
            throw new IllegalArgumentException("An attempt number starts at 1: " + attempt);
        }
    }

    /**
     * The key that identifies this action across attempts, restarts and replicas:
     * {@code <execution-id>:<path>}.
     *
     * <p>Replay alone does not make an effect happen once: an action that succeeds upstream and
     * crashes before its outcome is journaled runs again on resume. Only an executor that passes
     * this key to an upstream that deduplicates on it, such as an {@code Idempotency-Key} header,
     * has the effect once. The engine does not forward it to consumed operations yet.</p>
     */
    public String idempotencyKey() {
        return executionId.value() + ":" + path.value();
    }
}
