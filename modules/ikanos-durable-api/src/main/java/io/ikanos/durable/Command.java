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

import java.time.Duration;
import java.util.Objects;

/**
 * What an {@link Interpreter} asks the engine to do next. One command model serves every
 * durability mode: a {@code Runner} either carries the commands out inline, or journals them.
 *
 * <p>Actions returned in the same list may run concurrently; that is how a fork is expressed. The
 * join is the next call to {@link Interpreter#next}, which sees all their outcomes. A terminal
 * command ({@link Complete} or {@link Fail}) must be the only command of its list.</p>
 *
 * <p>The set grows by increment: compensation, continue-as-new and child executions are added by
 * later increments of the engine.</p>
 */
public sealed interface Command {

    /** The node this command is about, or {@code null} for a terminal command. */
    NodePath path();

    /**
     * Runs an action once its outcome is unknown. {@code action} is opaque to the engine and is
     * handed back to the {@link ActionExecutor} unchanged.
     */
    record ScheduleAction(NodePath path, Object action, RetryPolicy retry) implements Command {

        public ScheduleAction {
            Objects.requireNonNull(path, "path");
            retry = retry == null ? RetryPolicy.NONE : retry;
        }
    }

    /**
     * Records a decision (a branch taken, a map's item count, a loop's next step) so that replay
     * reads it back instead of evaluating it again.
     */
    record RecordDecision(NodePath path, Object value) implements Command {

        public RecordDecision {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Starts a durable timer; it fires once {@code delay} has passed in store time. */
    record StartTimer(NodePath path, Duration delay) implements Command {

        public StartTimer {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("A timer delay must not be negative");
            }
        }
    }

    /**
     * Waits for an update addressed to {@code path}, such as the caller's answer to an input
     * request. {@code request} describes what is asked and is opaque to the engine.
     */
    record AwaitUpdate(NodePath path, Object request) implements Command {

        public AwaitUpdate {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Ends the execution with a result. */
    record Complete(Object result) implements Command {

        @Override
        public NodePath path() {
            return null;
        }
    }

    /** Ends the execution with a failure. */
    record Fail(Failure failure) implements Command {

        public Fail {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public NodePath path() {
            return null;
        }
    }
}
