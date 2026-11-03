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
import java.util.Optional;

/**
 * What an {@link Interpreter} may know about an execution: its input and everything recorded so
 * far, keyed by node path. Every input to a decision comes from this view, which is what makes a
 * replay take the same decisions as the original run.
 */
public interface ExecutionView {

    /** The execution this view describes. */
    ExecutionId executionId();

    /** The input the execution started with. */
    Object input();

    /** The recorded outcome of the action at {@code path}, if it has finished. */
    Optional<ActionOutcome> outcome(NodePath path);

    /** The decision recorded at {@code path}, if any. */
    Optional<Object> decision(NodePath path);

    /** The update received for {@code path}, if any. */
    Optional<Object> update(NodePath path);

    /** Whether the timer started at {@code path} has fired. */
    boolean timerFired(NodePath path);

    /**
     * Current time, as the engine sees it: the store's clock in a durable mode, never a node's own
     * clock. An Interpreter that needs time reads it here, and records what it decides from it.
     */
    Instant now();
}
