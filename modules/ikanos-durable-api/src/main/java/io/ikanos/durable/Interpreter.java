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

/**
 * Walks an execution tree and decides what happens next. Ikanos implements it once, for every
 * durability mode.
 *
 * <p>An implementation must be <em>pure</em> with respect to the tree and the view: the same view
 * always yields the same commands. It must not read the clock, randomness or any state outside the
 * view, and must never schedule an action whose outcome the view already holds. Replay relies on
 * both rules.</p>
 */
@FunctionalInterface
public interface Interpreter {

    /**
     * Returns the next commands for the execution described by {@code view}: never an empty list.
     * Actions in the list may run concurrently; a terminal command stands alone.
     */
    List<Command> next(ExecutionView view);
}
