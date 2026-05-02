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
package io.naftiko.engine;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.observability.TelemetryBootstrap;
import io.naftiko.engine.step.StepHandler;
import io.naftiko.engine.step.StepHandlerRegistry;
import io.naftiko.spec.NaftikoSpec;

/**
 * Embedding entry point for using the Naftiko Framework as a library.
 *
 * <p>Loads a capability from YAML, registers step handlers that override normal step execution,
 * and manages the engine lifecycle (start/stop).
 *
 * <p>Usage:
 * <pre>{@code
 * NaftikoEngine engine = NaftikoEngine.builder()
 *     .capabilityFromClasspath("/my-capability.yml")
 *     .stepHandler("validate", new MyHandler())
 *     .build();
 * engine.start();
 * }</pre>
 */
public class NaftikoEngine {

    private final Capability capability;
    private final StepHandlerRegistry registry;

    NaftikoEngine(Capability capability, StepHandlerRegistry registry) {
        this.capability = capability;
        this.registry = registry;
    }

    /** Start the engine — starts all server and client adapters. */
    public void start() throws Exception {
        capability.start();
    }

    /** Stop the engine — stops all adapters gracefully. */
    public void stop() throws Exception {
        capability.stop();
    }

    /** Access the underlying capability (for advanced use cases). */
    public Capability getCapability() {
        return capability;
    }

    /** Access the step handler registry. */
    public StepHandlerRegistry getRegistry() {
        return registry;
    }

    /** Create a new builder for embedding the Naftiko engine. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for embedding the Naftiko engine as a library. Loads a capability from YAML
     * (classpath or file), optionally exposes the spec for post-load modification, and registers
     * step handlers.
     */
    public static class Builder {

        private NaftikoSpec spec;
        private String capabilityDir;
        private final StepHandlerRegistry registry = new StepHandlerRegistry();
        private boolean telemetryEnabled;

        /** Load a capability from a YAML resource on the classpath. */
        public Builder capabilityFromClasspath(String resourcePath) {
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IllegalArgumentException(
                            "Classpath resource not found: " + resourcePath);
                }
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                this.spec = mapper.readValue(is, NaftikoSpec.class);
                this.capabilityDir = null;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to load capability from classpath: " + resourcePath, e);
            }
            return this;
        }

        /** Load a capability from a YAML file. */
        public Builder capabilityFromFile(Path yamlPath) {
            File file = yamlPath.toFile();
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + yamlPath);
            }
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                this.spec = mapper.readValue(file, NaftikoSpec.class);
                this.capabilityDir = file.getParent();
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to load capability from file: " + yamlPath, e);
            }
            return this;
        }

        /** Set an already-loaded (and possibly modified) NaftikoSpec. */
        public Builder spec(NaftikoSpec spec) {
            this.spec = spec;
            return this;
        }

        /** Register a step handler by step name. Takes precedence over normal execution. */
        public Builder stepHandler(String stepName, StepHandler handler) {
            registry.register(stepName, handler);
            return this;
        }

        /**
         * Enable OpenTelemetry tracing and metrics. Disabled by default in embedded mode
         * for zero overhead. When enabled, the OTel SDK autoconfigure is used.
         */
        public Builder telemetry(boolean enabled) {
            this.telemetryEnabled = enabled;
            return this;
        }

        /** Build and return the engine (does not start it). */
        public NaftikoEngine build() {
            if (spec == null) {
                throw new IllegalStateException(
                        "No capability loaded. Call capabilityFromClasspath() or capabilityFromFile() first.");
            }
            try {
                if (telemetryEnabled) {
                    String serviceName = "naftiko";
                    if (spec.getInfo() != null && spec.getInfo().getLabel() != null) {
                        serviceName = "naftiko-" + spec.getInfo().getLabel();
                    }
                    TelemetryBootstrap.init(serviceName);
                }
                Capability capability = new Capability(spec, capabilityDir);
                capability.setStepHandlerRegistry(registry);
                return new NaftikoEngine(capability, registry);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize capability", e);
            }
        }
    }
}
