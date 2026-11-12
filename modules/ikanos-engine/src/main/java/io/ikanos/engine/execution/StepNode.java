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

import java.util.Objects;
import io.ikanos.durable.NodePath;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * One node of an {@link ExecutionTree}: a step at a stable path.
 *
 * @param path the node's path, {@code steps/<step-name>}
 * @param kind how the node runs
 * @param step the step specification
 * @param index the step index reported to telemetry, as before the execution tree
 */
public record StepNode(NodePath path, Kind kind, OperationStepSpec step, int index) {

    /** How a node runs. */
    public enum Kind {
        /** A {@code call} step: an outbound call through the consumed invocation carrier. */
        CALL,
        /** A {@code script} step: may read the clock or randomness, so it is an action. */
        SCRIPT,
        /** A step replaced by an embedder's {@code StepHandler}. */
        HANDLER,
        /** A {@code lookup} step: reads earlier outputs only, so it is recomputed, not recorded. */
        LOOKUP,
        /** An {@code elicit} step: a pause on the caller, resumed by an update at its path. */
        ELICIT,
        /** A step type the engine does not run; ignored, as before. */
        SKIP;

        /** Whether the node is an action, whose outcome a durable mode records. */
        public boolean isAction() {
            return this == CALL || this == SCRIPT || this == HANDLER;
        }
    }

    public StepNode {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }
}
