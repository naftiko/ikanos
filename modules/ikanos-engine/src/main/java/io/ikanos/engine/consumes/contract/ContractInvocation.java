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
package io.ikanos.engine.consumes.contract;

import java.util.Map;
import java.util.concurrent.Callable;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.observability.OtelNullSafety;
import io.ikanos.engine.observability.TelemetryBootstrap;

/**
 * One prepared call of a contract-described consumed adapter: the adapter supplies the call, the
 * span name and target, and the protocol's span attributes ({@code rpc.system},
 * {@code rpc.service}, {@code rpc.method}, ...). The call runs once, inside the client span.
 */
public class ContractInvocation extends ConsumedInvocation {

    /** The protocol's own result code, such as a gRPC status name. */
    public static final AttributeKey<String> ATTR_NATIVE_CODE =
            AttributeKey.stringKey("ikanos.consumed.native_code");

    private final String namespace;
    private final ConsumedOperationView operation;
    private final ClientSpanDescriptor descriptor;
    private final Map<AttributeKey<String>, String> attributes;
    private final Callable<ConsumedResult> call;

    /**
     * @param namespace the adapter's namespace
     * @param operation the operation's output declarations
     * @param descriptor the client span's operation and target
     * @param attributes the protocol's span attributes
     * @param call the call, run once
     */
    public ContractInvocation(String namespace, ConsumedOperationView operation,
            ClientSpanDescriptor descriptor, Map<AttributeKey<String>, String> attributes,
            Callable<ConsumedResult> call) {
        this.namespace = namespace;
        this.operation = operation;
        this.descriptor = descriptor;
        this.attributes = attributes != null ? attributes : Map.of();
        this.call = call;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public ConsumedOperationView operation() {
        return operation;
    }

    @Override
    protected ClientSpanDescriptor spanDescriptor() {
        return descriptor;
    }

    @Override
    protected ConsumedResult doInvoke() throws Exception {
        Span span = Span.current();
        attributes.forEach((key, value) -> {
            if (value != null) {
                span.setAttribute(OtelNullSafety.nonNullStringKey(key), value);
            }
        });
        return call.call();
    }

    @Override
    protected void annotateSpan(Span span, ConsumedResult result) {
        span.setAttribute(
                OtelNullSafety.nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                result.status());
        if (result instanceof ContractResult contract && contract.nativeCode() != null) {
            span.setAttribute(OtelNullSafety.nonNullStringKey(ATTR_NATIVE_CODE),
                    contract.nativeCode());
        }
        if (result.outcome() != Outcome.SUCCESS) {
            span.setStatus(StatusCode.ERROR, "status " + result.status());
        }
    }
}
