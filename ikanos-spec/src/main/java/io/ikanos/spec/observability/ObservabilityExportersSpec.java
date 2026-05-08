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
package io.ikanos.spec.observability;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Exporter configuration container.
 *
 * <h2>Thread safety</h2>
 * The {@code otlp} field is held in an {@link AtomicReference} so that future
 * fluent builders and Control-port runtime edits can replace the configuration
 * atomically while engine threads read it. This satisfies SonarQube rule
 * {@code java:S3077} (non-thread-safe types must not be {@code volatile}) and
 * preserves the public Jackson-facing API as a plain getter/setter pair.
 */
public class ObservabilityExportersSpec {

    private final AtomicReference<ObservabilityOtlpExporterSpec> otlp = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ObservabilityOtlpExporterSpec getOtlp() {
        return otlp.get();
    }

    public void setOtlp(ObservabilityOtlpExporterSpec otlp) {
        this.otlp.set(otlp);
    }
}
