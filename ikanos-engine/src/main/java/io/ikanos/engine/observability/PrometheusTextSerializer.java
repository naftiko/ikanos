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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.SumData;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Serializes a collection of OTel {@link MetricData} into Prometheus exposition text format.
 * Supports counters, gauges, and histograms — the three metric types used by the engine.
 */
class PrometheusTextSerializer {

    private PrometheusTextSerializer() {}

    static String serialize(Collection<MetricData> metrics) {
        StringBuilder sb = new StringBuilder();
        for (MetricData metric : metrics) {
            String name = sanitizeName(metric.getName());
            String description = metric.getDescription();

            switch (metric.getType()) {
                case LONG_SUM -> {
                    SumData<LongPointData> sum = metric.getLongSumData();
                    String type = sum.isMonotonic() ? "counter" : "gauge";
                    writeHeader(sb, name, type, description);
                    for (LongPointData point : sum.getPoints()) {
                        writeSample(sb, name, point.getAttributes(), point.getValue());
                    }
                }
                case DOUBLE_SUM -> {
                    SumData<DoublePointData> sum = metric.getDoubleSumData();
                    String type = sum.isMonotonic() ? "counter" : "gauge";
                    writeHeader(sb, name, type, description);
                    for (DoublePointData point : sum.getPoints()) {
                        writeSample(sb, name, point.getAttributes(), point.getValue());
                    }
                }
                case LONG_GAUGE -> {
                    writeHeader(sb, name, "gauge", description);
                    for (LongPointData point : metric.getLongGaugeData().getPoints()) {
                        writeSample(sb, name, point.getAttributes(), point.getValue());
                    }
                }
                case DOUBLE_GAUGE -> {
                    writeHeader(sb, name, "gauge", description);
                    for (DoublePointData point : metric.getDoubleGaugeData().getPoints()) {
                        writeSample(sb, name, point.getAttributes(), point.getValue());
                    }
                }
                case HISTOGRAM -> {
                    writeHeader(sb, name, "histogram", description);
                    HistogramData histData = metric.getHistogramData();
                    for (HistogramPointData point : histData.getPoints()) {
                        writeHistogramPoint(sb, name, point);
                    }
                }
                default -> {
                    // Skip unsupported metric types (summary, exponential histogram)
                }
            }
        }
        return sb.toString();
    }

    private static void writeHeader(StringBuilder sb, String name, String type,
            String description) {
        if (description != null && !description.isEmpty()) {
            sb.append("# HELP ").append(name).append(' ').append(description).append('\n');
        }
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void writeSample(StringBuilder sb, String name, Attributes attributes,
            Number value) {
        sb.append(name);
        writeLabels(sb, attributes);
        sb.append(' ').append(formatValue(value)).append('\n');
    }

    private static void writeHistogramPoint(StringBuilder sb, String name,
            HistogramPointData point) {
        Attributes attrs = point.getAttributes();
        List<Double> boundaries = point.getBoundaries();
        List<Long> counts = point.getCounts();
        long cumulativeCount = 0;

        for (int i = 0; i < counts.size(); i++) {
            cumulativeCount += counts.get(i);
            String le = i < boundaries.size()
                    ? formatValue(boundaries.get(i))
                    : "+Inf";
            sb.append(name).append("_bucket{");
            writeLabelPairs(sb, attrs);
            if (!attrs.isEmpty()) {
                sb.append(',');
            }
            sb.append("le=\"").append(le).append("\"} ")
                    .append(cumulativeCount).append('\n');
        }

        sb.append(name).append("_sum");
        writeLabels(sb, attrs);
        sb.append(' ').append(formatValue(point.getSum())).append('\n');

        sb.append(name).append("_count");
        writeLabels(sb, attrs);
        sb.append(' ').append(point.getCount()).append('\n');
    }

    private static void writeLabels(StringBuilder sb, Attributes attributes) {
        if (attributes.isEmpty()) {
            return;
        }
        sb.append('{');
        writeLabelPairs(sb, attributes);
        sb.append('}');
    }

    @SuppressWarnings("unchecked")
    private static void writeLabelPairs(StringBuilder sb, Attributes attributes) {
        boolean[] first = {true};
        attributes.forEach((key, value) -> {
            if (!first[0]) {
                sb.append(',');
            }
            first[0] = false;
            sb.append(sanitizeName(((AttributeKey<Object>) key).getKey()))
                    .append("=\"")
                    .append(escapeValue(String.valueOf(value)))
                    .append('"');
        });
    }

    private static String sanitizeName(String name) {
        return name.replace('.', '_').replace('-', '_');
    }

    private static String escapeValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String formatValue(Number value) {
        if (value instanceof Long) {
            return value.toString();
        }
        double d = value.doubleValue();
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.US, "%g", d);
    }
}
