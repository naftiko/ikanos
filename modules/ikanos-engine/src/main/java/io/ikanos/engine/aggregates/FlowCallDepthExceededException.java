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
package io.ikanos.engine.aggregates;

import java.util.List;

/**
 * Thrown when aggregate flows call each other more deeply than the configured limit.
 *
 * <p>Cycles between flows are rejected at load time, so this limit only guards against chains
 * that are legal but too deep. The message names the whole chain.</p>
 */
public class FlowCallDepthExceededException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> chain;
    private final int maxDepth;

    FlowCallDepthExceededException(List<String> chain, int maxDepth) {
        super("Flow call depth exceeds the limit of " + maxDepth + ": "
                + String.join(" -> ", chain));
        this.chain = List.copyOf(chain);
        this.maxDepth = maxDepth;
    }

    /** @return the qualified names of the flows on the call chain, outermost first */
    public List<String> getChain() {
        return chain;
    }

    /** @return the configured maximum depth */
    public int getMaxDepth() {
        return maxDepth;
    }
}
