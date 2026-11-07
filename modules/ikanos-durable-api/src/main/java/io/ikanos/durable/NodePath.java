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
 * Stable address of a node in an execution tree, such as {@code steps/gather/cargo/page[4]}.
 *
 * <p>A path is built from names and item indices, never from thread identity or execution order,
 * so it is the same on every replay of the same tree. It is the journal key of the node and the
 * second half of an action's idempotency key.</p>
 *
 * <p>Segments are separated by {@code /}. Two suffixes are reserved: {@code [n]} for the n-th item
 * of a repeated node (a {@code map} item, a page) and {@code @n} for the n-th visit of a node that
 * is entered again (a loop iteration). A first visit carries no {@code @} suffix.</p>
 *
 * @param value the path, without leading or trailing {@code /} and without empty segments
 */
public record NodePath(String value) {

    private static final String SEPARATOR = "/";

    public NodePath {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.startsWith(SEPARATOR) || value.endsWith(SEPARATOR)
                || value.contains(SEPARATOR + SEPARATOR)) {
            throw new IllegalArgumentException("Invalid node path: '" + value + "'");
        }
        for (String segment : value.split(SEPARATOR)) {
            checkSegment(segment);
        }
    }

    /** Returns the path of a top-level node. */
    public static NodePath of(String segment) {
        return new NodePath(segment);
    }

    /** Returns the path of a child of this node. */
    public NodePath child(String segment) {
        checkSegment(segment);
        return new NodePath(value + SEPARATOR + segment);
    }

    /** Returns the path of the {@code index}-th item of a repeated child, such as {@code page[4]}. */
    public NodePath item(String segment, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("An item index must not be negative: " + index);
        }
        return child(segment + "[" + index + "]");
    }

    /**
     * Returns the path of the {@code visit}-th visit of this node. The first visit is this path
     * itself; later visits append {@code @visit} to the last segment.
     */
    public NodePath visit(int visit) {
        if (visit < 1) {
            throw new IllegalArgumentException("A visit number starts at 1: " + visit);
        }
        return visit == 1 ? this : new NodePath(value + "@" + visit);
    }

    /** Returns {@code true} when {@code other} is this path or one of its descendants. */
    public boolean contains(NodePath other) {
        return other.value.equals(value) || other.value.startsWith(value + SEPARATOR);
    }

    static void checkSegment(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.isBlank() || segment.contains(SEPARATOR)) {
            throw new IllegalArgumentException("Invalid node path segment: '" + segment + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
