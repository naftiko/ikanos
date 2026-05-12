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
package io.ikanos.bootstrap;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.observability.ObservabilitySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class CapabilityRuntime {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityRuntime.class);

    public int serve(String filePath) {
        try {
            Capability capability = load(filePath);
            runUntilShutdown(capability);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "unexpected runtime error";
            System.err.println("Error: " + message);
            logger.error("Capability runtime failed", e);
            return 1;
        }
    }

    Capability load(String filePath) throws Exception {
        configureLogging();

        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        logger.info("Reading configuration from: {}", file.getAbsolutePath());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        String serviceName = "ikanos";
        if (spec.getInfo() != null && spec.getInfo().getLabel() != null) {
            serviceName = "ikanos-" + spec.getInfo().getLabel();
        }
        TelemetryBootstrap.init(serviceName, resolveObservabilitySpec(spec));

        return new Capability(spec, file.getParent());
    }

    void runUntilShutdown(Capability capability) throws Exception {
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Thread shutdownHook = new Thread(() -> {
            stopOnce(capability, stopped);
            shutdownLatch.countDown();
        }, "ikanos-shutdown");

        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            capability.start();
            logger.info("Capability started successfully.");
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopOnce(capability, stopped);
        } catch (Exception e) {
            stopOnce(capability, stopped);
            throw e;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already in progress.
            }
        }
    }

    ObservabilitySpec resolveObservabilitySpec(IkanosSpec spec) {
        if (spec.getCapability() == null || spec.getCapability().getExposes() == null) {
            return null;
        }

        for (ServerSpec server : spec.getCapability().getExposes()) {
            if (server instanceof ControlServerSpec controlSpec) {
                return controlSpec.getObservability();
            }
        }
        return null;
    }

    void configureLogging() {
        System.setProperty("org.restlet.engine.loggerFacadeClass",
                "org.restlet.ext.slf4j.Slf4jLoggerFacade");
        try {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
        } catch (NoClassDefFoundError e) {
            logger.debug("JUL-to-SLF4J bridge not available on the classpath", e);
        }
    }

    void stopOnce(Capability capability, AtomicBoolean stopped) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        try {
            capability.stop();
        } catch (Exception e) {
            logger.error("Error while stopping capability", e);
        }
    }
}
