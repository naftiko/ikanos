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
package io.ikanos.engine.exposes.mcp.handler;

import org.restlet.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.engine.aggregates.FlowCallValidator;
import io.ikanos.engine.execution.StepGuard;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.processor.CacheDataAppender;
import io.ikanos.engine.exposes.CacheScopes;
import io.ikanos.engine.exposes.mcp.processor.CacheHints;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;
import io.ikanos.engine.exposes.mcp.processor.ProtocolsVersionsValidator;
import io.ikanos.engine.exposes.mcp.processor.ServerDataAppender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_METHOD;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_NAME;
import static io.ikanos.engine.exposes.mcp.model.McpHeader.MCP_PROTOCOL_VERSION;
import io.ikanos.spec.util.IkanosMappers;

/**
 * A factory class for creating {@link McpCallHandler}s.
 */
public class McpCallHandlersFactory {

    private McpCallHandlersFactory() {
        // Utility class — no instances.
    }

    public static List<McpCallHandler> createAll(McpServerAdapter adapter) {
        Integer ttlMs = adapter.getMcpServerSpec().getTtlMs();
        if (ttlMs != null && ttlMs < 0) {
            // MCP 2026-07-28 (SEP-2549): a negative ttlMs is treated as 0, so the server starts
            // and sends 0. The schema's minimum still reports it to lint and ikanos validate.
            Context.getCurrentLogger().warning("MCP adapter '"
                    + adapter.getMcpServerSpec().getNamespace() + "': ttlMs " + ttlMs
                    + " is negative, sending 0 instead (SEP-2549)");
        }

        ObjectMapper mapper = IkanosMappers.json();
        DispatchPreProcessor protocolsVersionsValidator = new ProtocolsVersionsValidator(mapper);
        DispatchPostProcessor cacheDataAppender =
                new CacheDataAppender(CacheHints.forServer(adapter.getMcpServerSpec()));
        // Resolve every resource's hints now, so an invalid one fails at startup, not on a read,
        // naming the resource it comes from, and say which scope each result gets and why
        // (cacheScope: auto is derived)
        McpServerSpec spec = adapter.getMcpServerSpec();
        Logger logger = Context.getCurrentLogger();
        logger.fine(() -> describe(spec.getNamespace() + " lists",
                CacheHints.scopeDecision(spec, null)));
        for (McpServerResourceSpec resource : spec.getResources().values()) {
            if (resource.getTtlMs() != null && resource.getTtlMs() < 0) {
                logger.warning("MCP adapter '" + spec.getNamespace() + "', resource '"
                        + resource.getName() + "': ttlMs " + resource.getTtlMs()
                        + " is negative, sending 0 instead (SEP-2549)");
            }
            try {
                CacheHints.forResource(spec, resource);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "MCP resource '" + resource.getName() + "': " + e.getMessage(), e);
            }
            CacheScopes.Decision decision = CacheHints.scopeDecision(spec, resource);
            if (CacheScopes.PUBLIC.equals(decision.scope())
                    && readsCallerClaims(adapter.getCapability(), resource)) {
                // The content depends on the caller, which the request does not show, so a
                // shared cache would hand one caller's content to another (#998)
                throw new IllegalArgumentException("MCP resource '" + resource.getName()
                        + "': cacheScope public, but its call reads the caller's claims (a {{"
                        + Resolver.CALLER_CLAIMS + "...}} template, or a step guard that reads "
                        + Resolver.CALLER_ROOT + "), so its content depends on the caller."
                        + " Declare cacheScope private");
            }
            logger.fine(() -> describe(spec.getNamespace() + " resource " + resource.getName(),
                    decision));
        }
        DispatchPostProcessor serverDataAppender = new ServerDataAppender(adapter, mapper);

        List<McpCallHandler> handlers = new ArrayList<>(List.of(
                new ToolsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ToolsCallHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(serverDataAppender)),
                new ResourcesListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ResourcesReadHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new ResourcesTemplatesListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new PromptsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                new PromptsGetHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                        List.of(protocolsVersionsValidator), List.of(serverDataAppender)),
                new ServerDiscoverHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                        List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)),
                // Takes no headers or processors: legacy clients send neither MCP headers nor _meta.
                new LegacyInitializeHandler(adapter)
        ));

        // Skills Extension (SEP-2640) methods exist only when the server serves skills.
        if (adapter.getSkillHandler() != null && !adapter.getSkillHandler().isEmpty()) {
            handlers.add(new SkillsListHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
            handlers.add(new SkillsGetHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
            handlers.add(new ResourcesDirectoryReadHandler(adapter, List.of(MCP_PROTOCOL_VERSION, MCP_METHOD),
                    List.of(protocolsVersionsValidator), List.of(cacheDataAppender, serverDataAppender)));
        }
        // Tasks Extension (SEP-2663) methods exist only when a durable capability has task tools.
        if (adapter.servesTasks()) {
            handlers.add(new TasksGetHandler(adapter,
                    List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                    List.of(protocolsVersionsValidator), List.of(serverDataAppender)));
            handlers.add(new TasksCancelHandler(adapter,
                    List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                    List.of(protocolsVersionsValidator), List.of(serverDataAppender)));
            handlers.add(new TasksUpdateHandler(adapter,
                    List.of(MCP_PROTOCOL_VERSION, MCP_METHOD, MCP_NAME),
                    List.of(protocolsVersionsValidator), List.of(serverDataAppender)));
        }
        return List.copyOf(handlers);
    }

    /**
     * Step fields read as CEL expressions rather than templates: their top-level names, not
     * {@code {{...}}}, say whether they read the caller root.
     */
    private static final Set<String> EXPRESSION_FIELDS = Set.of("onlyIf", "successCriteria");

    /**
     * Whether reading {@code resource} may depend on a caller claim: a template that reads one,
     * or a step guard that reads the caller root (a step that runs only for some callers), in the
     * resource's own call, {@code with} and steps, or in what they call, transitively. A consumed
     * adapter counts whole; an aggregate flow counts with its own call, {@code with}, steps and
     * input parameter sets, then what it calls in turn ({@link FlowCallValidator#callsOf}), so a
     * sibling flow or an adapter nothing calls does not count. Every string and object key is
     * checked, as structured bodies render keys too (#998).
     */
    static boolean readsCallerClaims(Capability capability, McpServerResourceSpec resource) {
        ObjectMapper mapper = IkanosMappers.json();
        if (readsCallerClaims(mapper.valueToTree(resource.getCall()))
                || readsCallerClaims(mapper.valueToTree(resource.getWith()))
                || readsCallerClaims(mapper.valueToTree(resource.getSteps()))) {
            return true;
        }
        CapabilitySpec spec = capability == null || capability.getSpec() == null ? null
                : capability.getSpec().getCapability();
        if (spec == null) {
            return false;
        }
        Deque<String> pending = new ArrayDeque<>();
        if (resource.getCall() != null && resource.getCall().getOperation() != null) {
            pending.add(resource.getCall().getOperation());
        }
        for (OperationStepSpec step : resource.getSteps().values()) {
            if (step instanceof OperationStepCallSpec call && call.getCall() != null) {
                pending.add(call.getCall());
            }
        }
        // Flows by qualified name, adapters by namespace: each is scanned once, so a cycle ends
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String target = pending.poll();
            String namespace = namespaceOf(target);
            AggregateSpec aggregate = aggregateOf(spec, namespace);
            if (aggregate != null) {
                AggregateFlowSpec flow =
                        flowOf(aggregate, target.substring(target.indexOf('.') + 1));
                if (flow == null || !visited.add("flow:" + target)) {
                    // An unknown flow fails the load in FlowCallValidator
                    continue;
                }
                if (readsCallerClaims(mapper.valueToTree(flow))
                        || readsCallerClaims(mapper.valueToTree(inputSetsOf(aggregate, flow)))) {
                    return true;
                }
                pending.addAll(FlowCallValidator.callsOf(flow));
                continue;
            }
            if (!visited.add("consumes:" + namespace)) {
                continue;
            }
            for (ClientSpec client : spec.getConsumes()) {
                if (namespace != null && namespace.equals(client.getNamespace())
                        && readsCallerClaims(mapper.valueToTree(client))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AggregateSpec aggregateOf(CapabilitySpec spec, String namespace) {
        for (AggregateSpec candidate : spec.getAggregates().values()) {
            if (candidate != null && namespace != null
                    && namespace.equals(candidate.getNamespace())) {
                return candidate;
            }
        }
        return null;
    }

    private static AggregateFlowSpec flowOf(AggregateSpec aggregate, String name) {
        for (AggregateFlowSpec flow : aggregate.getFlows().values()) {
            if (flow != null && name.equals(flow.getName())) {
                return flow;
            }
        }
        return null;
    }

    /** The definitions' input parameter sets that {@code flow} names, which it renders too. */
    private static List<Object> inputSetsOf(AggregateSpec aggregate, AggregateFlowSpec flow) {
        List<Object> sets = new ArrayList<>();
        if (aggregate.getDefinitions() != null && flow.getInputParameterSets() != null) {
            for (String name : flow.getInputParameterSets()) {
                Object set = aggregate.getDefinitions().getInputParameterSets().get(name);
                if (set != null) {
                    sets.add(set);
                }
            }
        }
        return sets;
    }

    private static String namespaceOf(String operation) {
        if (operation == null) {
            return null;
        }
        int dot = operation.indexOf('.');
        return dot < 0 ? operation : operation.substring(0, dot);
    }

    /** Whether an expression field, or any expression of a list of them, reads the caller root. */
    private static boolean expressionReadsCaller(JsonNode value) {
        if (value.isString()) {
            return StepGuard.referencedNames(value.asString()).contains(Resolver.CALLER_ROOT);
        }
        for (JsonNode element : value.isArray() ? value : List.<JsonNode>of()) {
            if (expressionReadsCaller(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean readsCallerClaims(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isString()) {
            return Resolver.readsCallerClaims(node.asString());
        }
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            if (Resolver.readsCallerClaims(member.getKey())
                    || readsCallerClaims(member.getValue())
                    || EXPRESSION_FIELDS.contains(member.getKey())
                            && expressionReadsCaller(member.getValue())) {
                return true;
            }
        }
        for (JsonNode element : node.isArray() ? node : List.<JsonNode>of()) {
            if (readsCallerClaims(element)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(String subject, CacheScopes.Decision decision) {
        return "MCP adapter " + subject + ": cacheScope " + decision.scope() + " ("
                + decision.reason() + ")";
    }
}
