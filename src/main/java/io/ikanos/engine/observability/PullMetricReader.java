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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.Collection;
import java.util.Collections;

/**
 * A pull-based {@link MetricReader} that stores the SDK's {@link CollectionRegistration} and
 * exposes {@link #collectAllMetrics()} for on-demand retrieval. This avoids spinning up a
 * standalone HTTP server — the Control Port's {@code MetricsResource} calls this reader directly.
 *
 * <p>Uses {@link AggregationTemporality#CUMULATIVE} to match Prometheus expectations.</p>
 */
@SuppressWarnings("null")
class PullMetricReader implements MetricReader {

    private volatile CollectionRegistration registration = CollectionRegistration.noop();

    @Override
    public void register(CollectionRegistration registration) {
        this.registration = registration;
    }

    Collection<MetricData> collectAllMetrics() {
        CollectionRegistration reg = this.registration;
        return reg != null ? reg.collectAllMetrics() : Collections.emptyList();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode forceFlush() {
        collectAllMetrics();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
