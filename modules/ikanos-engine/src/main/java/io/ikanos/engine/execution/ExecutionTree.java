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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.ikanos.durable.NodePath;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.spec.scripting.OperationStepScriptSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepLookupSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.util.OperationStepElicitSpec;

/**
 * A compiled sequence of steps, walked by a {@link StepInterpreter}.
 *
 * <p>The steps of a flow, of an exposed operation, tool or resource, all compile the same way.
 * The map form of {@code steps:} runs in document order: the spec setters only accept maps with a
 * defined order, so iterating the map gives the order the author wrote. Each node's path is built
 * from its step name ({@code steps/<name>}), never from its position, so it stays stable when the
 * list form of {@code steps:} replaces the map form.</p>
 */
public final class ExecutionTree {

    static final NodePath STEPS = NodePath.of("steps");

    private final List<StepNode> nodes;

    private ExecutionTree(List<StepNode> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    /**
     * Compiles a {@code steps:} map. A step whose name has a handler in {@code registry} becomes a
     * {@link StepNode.Kind#HANDLER} node.
     *
     * @param steps the steps, in the order they run; may be {@code null} or empty
     * @param registry the embedder's step handlers; may be {@code null}
     */
    public static ExecutionTree compile(Map<String, OperationStepSpec> steps,
            StepHandlerRegistry registry) {
        List<StepNode> nodes = new ArrayList<>();
        if (steps == null) {
            return new ExecutionTree(nodes);
        }
        int index = 0;
        for (Map.Entry<String, OperationStepSpec> entry : steps.entrySet()) {
            OperationStepSpec step = entry.getValue();
            String name = step != null && step.getName() != null ? step.getName() : entry.getKey();
            NodePath path = STEPS.child(name);
            if (registry != null && step != null && step.getName() != null
                    && registry.has(step.getName())) {
                // Handler steps never advanced the telemetry step index; they still do not.
                nodes.add(new StepNode(path, StepNode.Kind.HANDLER, step, index));
                continue;
            }
            nodes.add(new StepNode(path, kindOf(step), step, index));
            index++;
        }
        return new ExecutionTree(nodes);
    }

    static StepNode.Kind kindOf(OperationStepSpec step) {
        return switch (step) {
            case OperationStepCallSpec call -> StepNode.Kind.CALL;
            case OperationStepLookupSpec lookup -> StepNode.Kind.LOOKUP;
            case OperationStepScriptSpec script -> StepNode.Kind.SCRIPT;
            case OperationStepElicitSpec elicit -> StepNode.Kind.ELICIT;
            case null, default -> StepNode.Kind.SKIP;
        };
    }

    /** The nodes, in the order they run. */
    public List<StepNode> nodes() {
        return nodes;
    }

    /** Whether the tree has no node. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
