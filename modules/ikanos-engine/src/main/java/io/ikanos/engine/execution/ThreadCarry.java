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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.MDC;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.util.OperationStepExecutor;

/**
 * The per-thread state of a request that steps run on another thread must keep: whether failed
 * steps keep the upstream error body (an MCP tool), the chain of flows being called (for
 * {@code maxCallDepth}), and the logging context ({@code trace_id}, {@code agent_id}). It is
 * captured on the thread that starts the steps and restored around them on the other thread.
 * The OpenTelemetry context, the caller's answers and the durable scope are carried by their
 * own owners.
 */
final class ThreadCarry {

    private final boolean keepErrorBodies;
    private final List<String> callChain;
    private final Map<String, String> logContext;

    private ThreadCarry(boolean keepErrorBodies, List<String> callChain,
            Map<String, String> logContext) {
        this.keepErrorBodies = keepErrorBodies;
        this.callChain = callChain;
        this.logContext = logContext;
    }

    /** The state of the current thread. */
    static ThreadCarry capture() {
        return new ThreadCarry(OperationStepExecutor.keepsUpstreamErrorBodies(),
                AggregateFlow.callChain(), MDC.getCopyOfContextMap());
    }

    /** Runs {@code work} on the current thread with the captured state, then restores its own. */
    <T> T run(Callable<T> work) throws Exception {
        Map<String, String> previousLog = MDC.getCopyOfContextMap();
        if (logContext != null) {
            MDC.setContextMap(logContext);
        }
        try {
            Callable<T> inChain = () -> AggregateFlow.withCallChain(callChain, work);
            if (keepErrorBodies) {
                try (OperationStepExecutor.UpstreamErrorBodies keep =
                        OperationStepExecutor.keepUpstreamErrorBodies()) {
                    return inChain.call();
                }
            }
            return inChain.call();
        } finally {
            if (previousLog != null) {
                MDC.setContextMap(previousLog);
            } else {
                MDC.clear();
            }
        }
    }
}
