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
package io.naftiko;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.engine.aggregates.Aggregate;
import io.naftiko.engine.aggregates.AggregateFunction;
import io.naftiko.engine.aggregates.AggregateRefResolver;
import io.naftiko.engine.step.StepHandler;
import io.naftiko.engine.step.StepHandlerRegistry;
import io.naftiko.engine.util.OperationStepExecutor;
import io.naftiko.spec.aggregates.AggregateSpec;
import io.naftiko.spec.util.ExecutionContext;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.ConsumesImportResolver;
import io.naftiko.engine.consumes.http.HttpClientAdapter;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.exposes.control.ControlServerAdapter;
import io.naftiko.engine.exposes.mcp.McpServerAdapter;
import io.naftiko.engine.exposes.rest.RestServerAdapter;
import io.naftiko.engine.exposes.skill.SkillServerAdapter;
import io.naftiko.engine.observability.TelemetryBootstrap;
import io.naftiko.engine.util.BindingResolver;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.http.HttpClientSpec;
import io.naftiko.spec.exposes.control.ControlServerSpec;
import io.naftiko.spec.exposes.control.ScriptingManagementSpec;
import io.naftiko.spec.exposes.rest.RestServerSpec;
import io.naftiko.spec.exposes.mcp.McpServerSpec;
import io.naftiko.spec.exposes.ServerSpec;
import io.naftiko.spec.exposes.skill.SkillServerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Main Capability class that initializes and manages adapters based on configuration
 */
public class Capability {

    private static final Logger logger = LoggerFactory.getLogger(Capability.class);

    /**
     * Mutable runtime state held in {@link AtomicReference}s. This satisfies SonarQube rule
     * {@code java:S3077} (volatile on non-thread-safe types) and prepares the engine for the
     * Control-port "hot reload" feature, where a new {@code NaftikoSpec} can be swapped in
     * atomically while request threads observe a consistent snapshot.
     *
     * <p>The list-typed slots (server/client adapters, aggregates) hold
     * {@link CopyOnWriteArrayList} instances internally so that iteration during request
     * handling is lock-free and safe even if a future hot-reload mutates the slot.</p>
     */
    private final AtomicReference<NaftikoSpec> spec = new AtomicReference<>();
    private final AtomicReference<List<ServerAdapter>> serverAdapters = new AtomicReference<>();
    private final AtomicReference<List<ClientAdapter>> clientAdapters = new AtomicReference<>();
    private final AtomicReference<List<Aggregate>> aggregates = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> bindings = new AtomicReference<>();
    private final AtomicReference<ScriptingManagementSpec> scriptingSpec = new AtomicReference<>();
    private final AtomicReference<StepHandlerRegistry> stepHandlerRegistry = new AtomicReference<>();

    public Capability(NaftikoSpec spec) throws Exception {
        this(spec, null);
    }

    /**
     * Creates a capability with an optional capability directory for binding file resolution.
     * 
     * @param spec The Naftiko specification
     * @param capabilityDir Directory containing the capability file (null for default)
     * @throws Exception if bindings cannot be resolved
     */
    public Capability(NaftikoSpec spec, String capabilityDir) throws Exception {
        this.spec.set(spec);

        // Resolve consumes imports early before initializing adapters
        if (spec.getCapability() != null && spec.getCapability().getConsumes() != null) {
            ConsumesImportResolver importResolver = new ConsumesImportResolver();
            importResolver.resolveImports(spec.getCapability().getConsumes(), capabilityDir);
        }

        // Resolve aggregate function refs (validate + derive MCP hints) before adapter init
        AggregateRefResolver aggregateRefResolver = new AggregateRefResolver();
        aggregateRefResolver.resolve(spec);

        // Find ScriptingManagementSpec from control adapter (if any) before building executors
        ScriptingManagementSpec resolvedScriptingSpec = null;
        for (ServerSpec serverSpec : spec.getCapability().getExposes()) {
            if (serverSpec instanceof ControlServerSpec controlSpec
                    && controlSpec.getManagement() != null
                    && controlSpec.getManagement().getScripting() != null) {
                resolvedScriptingSpec = controlSpec.getManagement().getScripting();
                break;
            }
        }
        this.scriptingSpec.set(resolvedScriptingSpec);

        // Build runtime aggregates (must happen after imports are resolved)
        List<Aggregate> aggregateList = new CopyOnWriteArrayList<>();
        if (spec.getCapability() != null && !spec.getCapability().getAggregates().isEmpty()) {
            OperationStepExecutor sharedExecutor = new OperationStepExecutor(this);
            for (AggregateSpec aggSpec : spec.getCapability().getAggregates()) {
                aggregateList.add(new Aggregate(aggSpec, sharedExecutor));
            }
        }
        this.aggregates.set(aggregateList);

        // Resolve bindings early for injection into adapters
        BindingResolver bindingResolver = new BindingResolver();
        ExecutionContext context = new ExecutionContext() {
            @Override
            public String getVariable(String key) {
                return System.getenv(key);
            }
        };
        Map<String, String> resolvedBinds = bindingResolver.resolve(spec.getBinds(), context);
        // Convert Map<String, String> to Map<String, Object> for compatibility
        this.bindings.set(new HashMap<>(resolvedBinds));

        // Initialize client adapters first
        List<ClientAdapter> clientList = new CopyOnWriteArrayList<>();

        // Then initialize server adapters with reference to source adapters
        List<ServerAdapter> serverList = new CopyOnWriteArrayList<>();

        if (spec.getCapability().getExposes().isEmpty()) {
            throw new IllegalArgumentException("Capability must expose at least one endpoint.");
        }

        for (ServerSpec serverSpec : spec.getCapability().getExposes()) {
            if ("rest".equals(serverSpec.getType())) {
                serverList.add(new RestServerAdapter(this, (RestServerSpec) serverSpec));
            } else if ("mcp".equals(serverSpec.getType())) {
                serverList.add(new McpServerAdapter(this, (McpServerSpec) serverSpec));
            } else if ("skill".equals(serverSpec.getType())) {
                serverList.add(new SkillServerAdapter(this, (SkillServerSpec) serverSpec));
            } else if ("control".equals(serverSpec.getType())) {
                serverList.add(new ControlServerAdapter(this, (ControlServerSpec) serverSpec));
            }
        }

        for (ClientSpec clientSpec : spec.getCapability().getConsumes()) {
            if ("http".equals(clientSpec.getType())) {
                clientList.add(new HttpClientAdapter(this, (HttpClientSpec) clientSpec));
            }
        }

        this.clientAdapters.set(clientList);
        this.serverAdapters.set(serverList);
    }

    public NaftikoSpec getSpec() {
        return spec.get();
    }

    public void setSpec(NaftikoSpec config) {
        this.spec.set(config);
    }

    public List<ClientAdapter> getClientAdapters() {
        return clientAdapters.get();
    }

    public List<ServerAdapter> getServerAdapters() {
        return serverAdapters.get();
    }

    public List<Aggregate> getAggregates() {
        return aggregates.get();
    }

    public ScriptingManagementSpec getScriptingSpec() {
        return scriptingSpec.get();
    }

    public StepHandlerRegistry getStepHandlerRegistry() {
        return stepHandlerRegistry.get();
    }

    public void setStepHandlerRegistry(StepHandlerRegistry registry) {
        this.stepHandlerRegistry.set(registry);
    }

    /**
     * Look up an aggregate function by ref key ({@code "namespace.functionName"}).
     *
     * @param ref the ref key, e.g. {@code "forecast.get-forecast"}
     * @return the matching {@link AggregateFunction}
     * @throws IllegalArgumentException if the ref cannot be resolved
     */
    public AggregateFunction lookupFunction(String ref) {
        int dot = ref.indexOf('.');
        if (dot <= 0 || dot == ref.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid aggregate function ref format: '" + ref
                            + "'. Expected 'namespace.functionName'");
        }
        String namespace = ref.substring(0, dot);
        String functionName = ref.substring(dot + 1);

        for (Aggregate agg : aggregates.get()) {
            if (agg.getNamespace().equals(namespace)) {
                AggregateFunction fn = agg.findFunction(functionName);
                if (fn != null) {
                    return fn;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown aggregate function ref: '" + ref + "'");
    }

    /**
     * Returns the map of resolved bindings. These are injected into parameter resolution contexts.
     * 
     * @return Map of variable name to resolved value
     */
    public Map<String, Object> getBindings() {
        return bindings.get();
    }

    public void start() throws Exception {
        for (ClientAdapter adapter : getClientAdapters()) {
            adapter.start();
        }

        for (ServerAdapter adapter : getServerAdapters()) {
            adapter.start();
        }

        NaftikoSpec currentSpec = spec.get();
        String capabilityName = currentSpec.getInfo() != null && currentSpec.getInfo().getLabel() != null
                ? currentSpec.getInfo().getLabel() : "unknown";
        TelemetryBootstrap.get().getMetrics().capabilityStarted(capabilityName);
    }

    public void stop() throws Exception {
        NaftikoSpec currentSpec = spec.get();
        String capabilityName = currentSpec.getInfo() != null && currentSpec.getInfo().getLabel() != null
                ? currentSpec.getInfo().getLabel() : "unknown";
        TelemetryBootstrap.get().getMetrics().capabilityStopped(capabilityName);

        for (ServerAdapter adapter : getServerAdapters()) {
            adapter.stop();
        }

        for (ClientAdapter adapter : getClientAdapters()) {
            adapter.stop();
        }
    }

    /** Create a new builder for embedding the Naftiko engine as a library. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for embedding the Naftiko engine as a library. Loads a capability from YAML
     * (classpath or file), optionally exposes the spec for post-load modification, and registers
     * step handlers.
     *
     * <p>Usage:
     * <pre>{@code
     * Capability capability = Capability.builder()
     *     .loadFromClasspath("/my-capability.yml")
     *     .stepHandler("validate", new MyHandler())
     *     .build();
     * capability.start();
     * }</pre>
     */
    public static class Builder {

        private NaftikoSpec spec;
        private String capabilityDir;
        private final StepHandlerRegistry registry = new StepHandlerRegistry();
        private boolean telemetryEnabled;

        /** Load a capability from a YAML resource on the classpath. */
        public Builder loadFromClasspath(String resourcePath) {
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
        public Builder loadFromFile(Path yamlPath) {
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

        /** Build and return the capability (does not start it). */
        public Capability build() {
            if (spec == null) {
                throw new IllegalStateException(
                        "No capability loaded. Call loadFromClasspath() or loadFromFile() first.");
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
                return capability;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize capability", e);
            }
        }
    }

    /**
     * Launch the capability, reading its configuration from local NAFTIKO.yaml file unless a
     * specific name is provided.
     * 
     * @param args The optional part and name of the capability configuration file.
     */
    public static void main(String[] args) {
        // Route Restlet logging through SLF4J before any context is created
        System.setProperty("org.restlet.engine.loggerFacadeClass",
                "org.restlet.ext.slf4j.Slf4jLoggerFacade");
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        // Determine file path: Argument if provided, otherwise default
        String filePath = (args.length > 0) ? args[0] : "naftiko.yaml";

        File file = new File(filePath);
        logger.info("Reading configuration from: {}", file.getAbsolutePath());

        // Read the configuraton file
        if (file.exists()) {
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                // Ignore unknown properties to handle potential Restlet framework classes
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
                // Initialize OpenTelemetry with service name from spec
                String serviceName = "naftiko";
                if (spec.getInfo() != null && spec.getInfo().getLabel() != null) {
                    serviceName = "naftiko-" + spec.getInfo().getLabel();
                }
                io.naftiko.spec.observability.ObservabilitySpec observabilitySpec = null;
                if (spec.getCapability() != null) {
                    for (io.naftiko.spec.exposes.ServerSpec server : spec.getCapability().getExposes()) {
                        if (server instanceof io.naftiko.spec.exposes.control.ControlServerSpec controlSpec) {
                            observabilitySpec = controlSpec.getObservability();
                            break;
                        }
                    }
                }
                TelemetryBootstrap.init(serviceName, observabilitySpec);
                // Pass the capability directory for bind file resolution
                String capabilityDir = file.getParent();
                Capability capability = new Capability(spec, capabilityDir);
                capability.start();
                logger.info("Capability started successfully.");
            } catch (Exception e) {
                logger.error("Error reading file", e);
            }
        } else {
            logger.error("Error: File not found at {}", filePath);
            System.exit(1);
        }
    }

}
