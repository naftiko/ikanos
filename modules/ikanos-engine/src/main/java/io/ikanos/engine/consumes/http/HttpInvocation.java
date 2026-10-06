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
package io.ikanos.engine.consumes.http;

import org.restlet.Request;
import org.restlet.Response;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.observability.OtelNullSafety;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * A prepared HTTP call: a fully built Restlet {@link Request} bound to its
 * {@link HttpClientAdapter}.
 *
 * <p>Built by {@link HttpClientAdapter#prepare(String, java.util.Map)}. Span attributes and the
 * {@code recordHttpClient} metric are the same as before the carrier refactor.</p>
 */
public class HttpInvocation extends ConsumedInvocation {

    private final HttpClientAdapter adapter;
    private final HttpClientOperationSpec operationSpec;
    private final Request request;
    private final Response response;
    private final ConsumedOperationView operation;

    HttpInvocation(HttpClientAdapter adapter, HttpClientOperationSpec operationSpec,
            Request request) {
        this.adapter = adapter;
        this.operationSpec = operationSpec;
        this.request = request;
        this.response = new Response(request);
        this.operation = ConsumedOperationView.of(operationSpec,
                operationSpec != null ? operationSpec.getOutputMediaType() : null,
                operationSpec != null ? operationSpec.getMaxBinarySize() : null);
    }

    /** @return the adapter that prepared this call */
    public HttpClientAdapter getAdapter() {
        return adapter;
    }

    /** @return the consumed operation */
    public HttpClientOperationSpec getOperationSpec() {
        return operationSpec;
    }

    /** @return the prepared Restlet request (not yet sent until {@link #invoke()}) */
    public Request getRequest() {
        return request;
    }

    @Override
    public String namespace() {
        return adapter.getHttpClientSpec().getNamespace();
    }

    @Override
    public ConsumedOperationView operation() {
        return operation;
    }

    @Override
    protected ClientSpanDescriptor spanDescriptor() {
        String method = request.getMethod() != null ? request.getMethod().getName() : "UNKNOWN";
        String url = request.getResourceRef() != null
                ? request.getResourceRef().toString()
                : "unknown";
        return new ClientSpanDescriptor(method, url);
    }

    @Override
    protected ConsumedResult doInvoke() {
        // Inject W3C trace context after the client span is current so downstream services see
        // this span as the parent.
        OtelRestletBridge.injectContext(request);
        adapter.getHttpClient().handle(request, response);
        return new HttpConsumedResult(operationSpec, response);
    }

    /**
     * Kept instead of the base default on purpose: the span-status message stays {@code "HTTP 503"}
     * (unchanged from before the carrier refactor, so existing trace queries keep matching), and no
     * status-code attribute is written when Restlet reports no status, whereas the base default
     * would record {@code 0}.
     */
    @Override
    protected void annotateSpan(Span span, ConsumedResult result) {
        if (response.getStatus() != null) {
            int statusCode = response.getStatus().getCode();
            span.setAttribute(
                    OtelNullSafety.nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                    statusCode);
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        }
    }

    @Override
    protected void recordMetrics(TelemetryBootstrap telemetry, ConsumedResult result,
            double durationSec) {
        String method = request.getMethod() != null ? request.getMethod().getName() : "UNKNOWN";
        String host = request.getResourceRef() != null
                ? request.getResourceRef().getHostDomain()
                : "unknown";
        int code = response.getStatus() != null ? response.getStatus().getCode() : 0;
        telemetry.getMetrics().recordHttpClient(method, host != null ? host : "unknown", code,
                durationSec);
    }
}
