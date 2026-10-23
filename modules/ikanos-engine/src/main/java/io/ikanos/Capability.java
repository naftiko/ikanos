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
package io.ikanos;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.aggregates.Aggregate;
import io.ikanos.engine.aggregates.AggregateFlow;
import io.ikanos.engine.aggregates.AggregateRefResolver;
import io.ikanos.engine.aggregates.FlowCallValidator;
import io.ikanos.engine.step.StepHandler;
import io.ikanos.engine.step.StepHandlerRegistry;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.util.ExecutionContext;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.imports.AggregatesImportStrategy;
import io.ikanos.engine.imports.BindsImportStrategy;
import io.ikanos.engine.imports.ConsumesImportStrategy;
import io.ikanos.engine.imports.ExposesImportStrategy;
import io.ikanos.engine.imports.ImportResolver;
import io.ikanos.engine.imports.NamespaceUniquenessValidator;
import io.ikanos.engine.imports.SourceFileLoader;
import io.ikanos.engine.consumes.ClientAdapterRegistry;
import io.ikanos.engine.consumes.ConsumedReferenceValidator;
import io.ikanos.engine.consumes.ConsumedTemplateValidator;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.engine.consumes.tunnel.TunnelBootstrap;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.control.ControlServerAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.rest.RestServerAdapter;
import io.ikanos.engine.exposes.skill.SkillServerAdapter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.BindingResolver;
import io.ikanos.engine.util.QualifiedName;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.exposes.control.FlowManagementSpec;
import io.ikanos.spec.exposes.control.ScriptingManagementSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Main Capability class that initializes and manages adapters based on configuration
 */
public class Capability {

    /**
     * Mutable runtime state held in {@link AtomicReference}s. This satisfies SonarQube rule
     * {@code java:S3077} (volatile on non-thread-safe types) and prepares the engine for the
     * Control-port "hot reload" feature, where a new {@code IkanosSpec} can be swapped in
     * atomically while request threads observe a consistent snapshot.
     *
     * <p>The list-typed slots (server/client adapters, aggregates) hold
     * {@link CopyOnWriteArrayList} instances internally so that iteration during request
     * handling is lock-free and safe even if a future hot-reload mutates the slot.</p>
     */
    private final AtomicReference<IkanosSpec> spec = new AtomicReference<>();
    private final AtomicReference<List<ServerAdapter>> serverAdapters = new AtomicReference<>();
    private final AtomicReference<List<ClientAdapter>> clientAdapters = new AtomicReference<>();
    private final AtomicReference<List<Aggregate>> aggregates = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> bindings = new AtomicReference<>();
    private final AtomicReference<ScriptingManagementSpec> scriptingSpec = new AtomicReference<>();
    private final AtomicReference<StepHandlerRegistry> stepHandlerRegistry = new AtomicReference<>();

    public Capability(IkanosSpec spec) throws Exception {
        this(spec, null);
    }

    /**
     * Creates a capability with an optional capability directory for binding file resolution.
     * 
     * @param spec The Ikanos specification
     * @param capabilityDir Directory containing the capability file (null for default)
     * @throws Exception if bindings cannot be resolved
     */
    public Capability(IkanosSpec spec, String capabilityDir) throws Exception {
        this.spec.set(spec);

        // Resolve imports across all four sections using the unified import mechanism.
        // A shared SourceFileLoader caches parsed source files across section resolvers.
        if (spec.getCapability() != null) {
            Path capabilityPath = (capabilityDir != null && !capabilityDir.isEmpty())
                    ? Path.of(capabilityDir) : null;

            SourceFileLoader loader = new SourceFileLoader();

            // §10 resolver pass order: consumes → aggregates → exposes → binds
            new ImportResolver<>(new ConsumesImportStrategy(), loader)
                    .resolveAll(spec.getCapability().getConsumes(), capabilityPath);
            // capability.aggregates is a Map<String, AggregateSpec>; resolve imports via a
            // mutable list snapshot, then write resolved entries back by namespace key.
            Map<String, AggregateSpec> aggregatesMap = spec.getCapability().getAggregates();
            if (aggregatesMap != null && !aggregatesMap.isEmpty()) {
                List<AggregateSpec> aggregatesList = new ArrayList<>(aggregatesMap.values());
                new ImportResolver<>(new AggregatesImportStrategy(), loader)
                        .resolveAll(aggregatesList, capabilityPath);
                Map<String, AggregateSpec> resolved = new LinkedHashMap<>();
                for (AggregateSpec agg : aggregatesList) {
                    resolved.put(agg.getNamespace(), agg);
                }
                spec.getCapability().setAggregates(resolved);
            }
            new ImportResolver<>(new ExposesImportStrategy(), loader)
                    .resolveAll(spec.getCapability().getExposes(), capabilityPath);
            new ImportResolver<>(new BindsImportStrategy(), loader)
                    .resolveAll(spec.getCapability().getBinds(), capabilityPath);
        }

        // A namespace has one owner across all sections, so `call: namespace.name` resolves to
        // either a consumed operation or an aggregate flow, never both.
        NamespaceUniquenessValidator.validate(spec);

        // Resolve aggregate function refs (validate + derive MCP hints) before adapter init
        AggregateRefResolver aggregateRefResolver = new AggregateRefResolver();
        aggregateRefResolver.resolve(spec);

        // Validate calls between aggregate flows: known targets, no cycles, no `call:` from an
        // exposed unit to a flow (exposed units use `ref:`).
        FlowCallValidator.validate(spec);

        // Find management settings from the control adapter (if any) before building executors
        ScriptingManagementSpec resolvedScriptingSpec = null;
        int maxFlowCallDepth = FlowManagementSpec.DEFAULT_MAX_CALL_DEPTH;
        for (ServerSpec serverSpec : spec.getCapability().getExposes()) {
            if (serverSpec instanceof ControlServerSpec controlSpec
                    && controlSpec.getManagement() != null) {
                resolvedScriptingSpec = controlSpec.getManagement().getScripting();
                if (controlSpec.getManagement().getFlows() != null) {
                    maxFlowCallDepth = controlSpec.getManagement().getFlows().getMaxCallDepth();
                }
                break;
            }
        }
        this.scriptingSpec.set(resolvedScriptingSpec);

        // Build runtime aggregates (must happen after imports are resolved). The executor is
        // shared: each flow passes its own namespace when it calls it.
        List<Aggregate> aggregateList = new CopyOnWriteArrayList<>();
        if (!spec.getCapability().getAggregates().isEmpty()) {
            OperationStepExecutor sharedExecutor = new OperationStepExecutor(this);
            for (AggregateSpec aggSpec : spec.getCapability().getAggregates().values()) {
                aggregateList.add(new Aggregate(aggSpec, sharedExecutor, maxFlowCallDepth));
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

        // NOTE: `this` escapes to adapter constructors below before clientAdapters
        // and serverAdapters are published via AtomicReference.set() at the end of
        // this constructor. Adapter constructors MUST NOT call getClientAdapters()
        // or getServerAdapters() — those references are still null/unset at this
        // point. Defer any such cross-adapter lookup to a post-construction
        // lifecycle method (e.g. start()/init()) that runs after the constructor
        // returns. The AtomicReference migration (S3077) addresses field-level
        // visibility, not this construction-time publication ordering.
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

        // Discover and start any reverse-tunnel transports declared by consumes-blocks
        // BEFORE constructing HttpClientAdapters, so the adapter constructors receive a
        // started (or empty) tunnel map. The 30s default readiness gate matches the
        // §6.5 design in blueprints/reverse-tunnel-private-network.md.
        // Templates are checked first, so a capability that cannot work never starts a tunnel.
        ConsumedTemplateValidator.validate(spec);
        Map<String, Tunnel> tunnels = TunnelBootstrap.discoverAndStart(
                spec.getCapability().getConsumes(),
                java.time.Duration.ofSeconds(30),
                this.bindings.get());

        for (ClientSpec clientSpec : spec.getCapability().getConsumes()) {
            clientList.add(ClientAdapterRegistry.getDefault().create(this, clientSpec, tunnels));
        }

        // Check call and forward references against the consumed adapters now that they exist,
        // so a spec mistake fails at load time rather than on the first request. Tunnels were
        // already started above; close them if the capability is rejected.
        try {
            ConsumedReferenceValidator.validate(spec.getCapability(), clientList);
        } catch (IllegalArgumentException e) {
            for (Tunnel tunnel : tunnels.values()) {
                try {
                    tunnel.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }

        this.clientAdapters.set(clientList);
        this.serverAdapters.set(serverList);
    }

    public IkanosSpec getSpec() {
        return spec.get();
    }

    public void setSpec(IkanosSpec config) {
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
     * Look up an aggregate flow by ref key ({@code "namespace.flowName"}).
     *
     * @param ref the ref key, e.g. {@code "forecast.get-forecast"}
     * @return the matching {@link AggregateFlow}
     * @throws IllegalArgumentException if the ref cannot be resolved
     */
    public AggregateFlow lookupFlow(String ref) {
        QualifiedName name = QualifiedName.parse(ref);
        if (name == null) {
            throw new IllegalArgumentException(
                    "Invalid aggregate flow ref format: '" + ref
                            + "'. Expected 'namespace.flowName'");
        }
        AggregateFlow flow = findFlow(name.namespace(), name.name());
        if (flow == null) {
            throw new IllegalArgumentException(
                    "Unknown aggregate flow ref: '" + ref + "'");
        }
        return flow;
    }

    /**
     * Find an aggregate flow by namespace and name.
     *
     * @param namespace the aggregate namespace
     * @param flowName  the flow name
     * @return the flow, or {@code null} when there is no such flow
     */
    public AggregateFlow findFlow(String namespace, String flowName) {
        List<Aggregate> current = aggregates.get();
        if (current == null || namespace == null) {
            return null;
        }
        for (Aggregate agg : current) {
            if (namespace.equals(agg.getNamespace())) {
                AggregateFlow flow = agg.findFlow(flowName);
                if (flow != null) {
                    return flow;
                }
            }
        }
        return null;
    }

    /**
     * @param namespace a namespace
     * @return {@code true} when an aggregate declares {@code namespace}
     */
    public boolean hasAggregate(String namespace) {
        List<Aggregate> current = aggregates.get();
        if (current == null || namespace == null) {
            return false;
        }
        for (Aggregate agg : current) {
            if (namespace.equals(agg.getNamespace())) {
                return true;
            }
        }
        return false;
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

        IkanosSpec currentSpec = spec.get();
        String capabilityName = currentSpec.getInfo() != null && currentSpec.getInfo().getDisplay() != null
                ? currentSpec.getInfo().getDisplay() : "unknown";
        TelemetryBootstrap.get().getMetrics().capabilityStarted(capabilityName);
    }

    public void stop() throws Exception {
        IkanosSpec currentSpec = spec.get();
        String capabilityName = currentSpec.getInfo() != null && currentSpec.getInfo().getDisplay() != null
                ? currentSpec.getInfo().getDisplay() : "unknown";
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

        private IkanosSpec spec;
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
                ObjectMapper mapper = IkanosMappers.lenientYaml();
                this.spec = mapper.readValue(is, IkanosSpec.class);
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
                ObjectMapper mapper = IkanosMappers.lenientYaml();
                this.spec = mapper.readValue(file, IkanosSpec.class);
                this.capabilityDir = file.getParent();
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Failed to load capability from file: " + yamlPath, e);
            }
            return this;
        }

        /** Set an already-loaded (and possibly modified) IkanosSpec. */
        public Builder spec(IkanosSpec spec) {
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
                    String serviceName = "ikanos";
                    if (spec.getInfo() != null && spec.getInfo().getDisplay() != null) {
                        serviceName = "ikanos-" + spec.getInfo().getDisplay();
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

}
