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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression test for #800: an application that embeds the engine without the optional
 * {@code opentelemetry-sdk} must still be able to build a capability and call its tools.
 *
 * <p>The work runs in an isolated class loader built from the test classpath minus every
 * OpenTelemetry SDK, exporter and Logback appender jar, so only {@code opentelemetry-api} is
 * visible, exactly as for an embedder that does not opt in to telemetry. It is done by
 * {@link EmbeddedToolCallProbe}, loaded through that class loader and called through
 * {@link Callable}, which both loaders share. If the classpath is shortened into a single
 * manifest jar (some IDE launchers), the SDK cannot be filtered out and the setup assertion
 * fails with an explicit message.</p>
 */
class TelemetryWithoutSdkTest {

    private static final String PROBE = "io.ikanos.engine.observability.EmbeddedToolCallProbe";

    @ParameterizedTest(name = "with control port: {0}")
    @ValueSource(booleans = {false, true})
    void toolCallShouldSucceedWhenOtelSdkIsAbsent(boolean withControlPort) throws Exception {
        try (URLClassLoader loader = classLoaderWithoutOtelSdk()) {
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("io.opentelemetry.sdk.trace.SpanProcessor"),
                    "test setup: the OpenTelemetry SDK must not be visible to the probe");

            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            String result;
            try {
                @SuppressWarnings("unchecked")
                Callable<String> probe = (Callable<String>) loader.loadClass(PROBE)
                        .getDeclaredConstructor(boolean.class).newInstance(withControlPort);
                result = probe.call();
            } catch (LinkageError e) {
                throw new AssertionError("#800: building the capability or calling its tool "
                        + "needed opentelemetry-sdk; with telemetry off the engine must only "
                        + "need opentelemetry-api", e);
            } finally {
                thread.setContextClassLoader(previous);
            }

            assertFalse(result.startsWith("error:"),
                    "#800: the tool call returned an error without opentelemetry-sdk: " + result);
            assertTrue(result.contains("hello"),
                    "#800: expected the tool output without opentelemetry-sdk, got: " + result);
        }
    }

    /** The Surefire test classpath, without OpenTelemetry SDK, exporter or appender jars. */
    private static URLClassLoader classLoaderWithoutOtelSdk() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.startsWith("opentelemetry-sdk")
                    || name.startsWith("opentelemetry-exporter")
                    || name.startsWith("opentelemetry-logback-appender")) {
                continue;
            }
            urls.add(Path.of(entry).toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
