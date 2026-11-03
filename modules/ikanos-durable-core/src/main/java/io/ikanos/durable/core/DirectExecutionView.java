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
package io.ikanos.durable.core;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.NodePath;

/**
 * The view of an execution run by the {@link DirectRunner}: plain maps in the request thread,
 * written only between two calls to the Interpreter, and dropped when the run ends.
 */
final class DirectExecutionView implements ExecutionView {

    private final ExecutionRequest request;
    private final Clock clock;
    private final Map<NodePath, ActionOutcome> outcomes = new HashMap<>();
    private final Map<NodePath, Object> decisions = new HashMap<>();

    DirectExecutionView(ExecutionRequest request, Clock clock) {
        this.request = request;
        this.clock = clock;
    }

    @Override
    public ExecutionId executionId() {
        return request.executionId();
    }

    @Override
    public Object input() {
        return request.input();
    }

    @Override
    public Optional<ActionOutcome> outcome(NodePath path) {
        return Optional.ofNullable(outcomes.get(path));
    }

    @Override
    public Optional<Object> decision(NodePath path) {
        return Optional.ofNullable(decisions.get(path));
    }

    @Override
    public Optional<Object> update(NodePath path) {
        return Optional.ofNullable(request.updates().get(path));
    }

    @Override
    public boolean timerFired(NodePath path) {
        return false;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    boolean hasOutcome(NodePath path) {
        return outcomes.containsKey(path);
    }

    boolean hasDecision(NodePath path) {
        return decisions.containsKey(path);
    }

    void recordOutcome(NodePath path, ActionOutcome outcome) {
        outcomes.put(path, outcome);
    }

    void recordDecision(NodePath path, Object value) {
        decisions.put(path, value);
    }
}
