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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import java.util.logging.Logger;

/**
 * The JVM's own metrics (CPU, heap and non-heap memory, garbage collection, threads, classes), from
 * OpenTelemetry's runtime telemetry, recorded on the engine's meter provider so that the Control
 * Port's {@code /metrics} serves them next to the request metrics (issue #737).
 *
 * <p>{@code opentelemetry-runtime-telemetry} is optional, like the SDK: {@link SdkTelemetry} only
 * calls {@link #start(OpenTelemetry)} once {@link #available()} says it is on the classpath, and only
 * this class refers to its types.</p>
 */
final class RuntimeMetrics {

    static final String RUNTIME_TELEMETRY_CLASS =
            "io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry";

    private static final Logger logger = Logger.getLogger(RuntimeMetrics.class.getName());

    private RuntimeMetrics() {
    }

    static boolean available() {
        try {
            Class.forName(RUNTIME_TELEMETRY_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            logger.info("OpenTelemetry runtime telemetry not on classpath — no JVM metrics");
            return false;
        }
    }

    /**
     * Starts recording the JVM metrics; closing the result stops it. Returns {@code null} when they can't be
     * read here, such as in a native image without the management beans: the rest of telemetry still works.
     */
    static AutoCloseable start(OpenTelemetry openTelemetry) {
        try {
            return RuntimeTelemetry.create(openTelemetry);
        } catch (RuntimeException | LinkageError e) {
            logger.warning("JVM metrics unavailable \u2014 serving the others without them: " + e.getMessage());
            return null;
        }
    }
}
