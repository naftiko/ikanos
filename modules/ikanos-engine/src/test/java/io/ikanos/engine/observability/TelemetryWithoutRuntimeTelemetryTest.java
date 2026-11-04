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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code opentelemetry-runtime-telemetry} is optional (issue #737): an application that embeds the
 * engine with the OpenTelemetry SDK but without it still gets its telemetry, only without the JVM
 * metrics.
 */
class TelemetryWithoutRuntimeTelemetryTest {

    @Test
    void telemetryShouldWorkWithoutTheJvmMetricsWhenRuntimeTelemetryIsAbsent() throws Exception {
        try (URLClassLoader loader = classLoaderWithoutRuntimeTelemetry()) {
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass(RuntimeMetrics.RUNTIME_TELEMETRY_CLASS),
                    "test setup: runtime telemetry must not be visible");

            Class<?> bootstrap = loader.loadClass(TelemetryBootstrap.class.getName());
            Object telemetry = bootstrap.getMethod("init", String.class).invoke(null, "embedded");
            assertTrue((Boolean) bootstrap.getMethod("isSdkActive").invoke(telemetry),
                    "the SDK is present, so telemetry must be on");

            String text = (String) bootstrap.getMethod("collectPrometheusMetrics").invoke(telemetry);
            assertNotNull(text, "Prometheus metrics should still be served");
            assertFalse(text.contains("jvm_"), "No JVM metrics without runtime telemetry: " + text);
        }
    }

    /** The Surefire test classpath, without the runtime telemetry jar. */
    private static URLClassLoader classLoaderWithoutRuntimeTelemetry() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!Path.of(entry).getFileName().toString().startsWith("opentelemetry-runtime-telemetry")) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
