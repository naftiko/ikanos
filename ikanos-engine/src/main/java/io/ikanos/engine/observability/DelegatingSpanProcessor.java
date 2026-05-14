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
package io.ikanos.engine.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * A {@link SpanProcessor} that delegates to another processor set after construction.
 *
 * <p>Registered at OTel SDK build time as a placeholder. Once the actual processor is
 * available (e.g. when the control adapter creates the {@code TraceRingBuffer}), call
 * {@link #setDelegate(SpanProcessor)} to start forwarding spans.</p>
 *
 * <p>Thread-safe: the delegate reference is volatile so updates are visible to all
 * exporter threads immediately.</p>
 */
class DelegatingSpanProcessor implements SpanProcessor {

    private volatile SpanProcessor delegate;

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        SpanProcessor d = delegate;
        if (d != null) {
            d.onStart(parentContext, span);
        }
    }

    @Override
    public boolean isStartRequired() {
        SpanProcessor d = delegate;
        return d != null && d.isStartRequired();
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanProcessor d = delegate;
        if (d != null) {
            d.onEnd(span);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true; // Always required so we don't miss spans set after build
    }

    @Override
    public CompletableResultCode shutdown() {
        SpanProcessor d = delegate;
        if (d != null) {
            return d.shutdown();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        SpanProcessor d = delegate;
        if (d != null) {
            return d.forceFlush();
        }
        return CompletableResultCode.ofSuccess();
    }

    void setDelegate(SpanProcessor delegate) {
        this.delegate = delegate;
    }

    SpanProcessor getDelegate() {
        return delegate;
    }
}
