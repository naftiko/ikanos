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
package io.ikanos.engine.consumes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.ikanos.engine.consumes.http.HttpClientAdapter;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.rest.RestServerForwardSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Checks, at capability load time, the references from exposes and aggregates to consumed
 * adapters, so that a spec mistake fails at startup instead of on the first request.
 *
 * <ul>
 *   <li>A {@code call:} ({@code namespace.operation}) whose namespace is a consumed adapter must
 *       name an operation that adapter declares. A namespace that no consumed adapter declares is
 *       not checked here: exposers fall back to mock responses in that case.</li>
 *   <li>A REST {@code forward.targetNamespace} must name a consumed adapter of {@code type: http},
 *       the only protocol forward mode supports.</li>
 *   <li>When that adapter signs with {@code awsSigV4}, {@code forward.trustedHeaders} must not
 *       list an {@code x-amz-*} header. AWS reads several of them as part of the operation
 *       ({@code x-amz-copy-source}, {@code x-amz-acl}, {@code x-amz-grant-*},
 *       {@code x-amz-website-redirect-location}...), and a trusted one is signed with the
 *       capability's key, so a caller could reach objects outside the forwarded prefix (#954).
 *       It also keeps the engine's own {@code X-Amz-Date}, {@code X-Amz-Security-Token} and
 *       {@code X-Amz-Content-Sha256} from being set by a caller.</li>
 * </ul>
 */
public final class ConsumedReferenceValidator {

    /** The prefix, in lower case, of the AWS headers a forward to an awsSigV4 adapter refuses. */
    private static final String AMZ_HEADER_PREFIX = "x-amz-";

    private ConsumedReferenceValidator() {
    }

    /**
     * @param capability     the capability spec; {@code null} is a no-op
     * @param clientAdapters the consumed adapters built from {@code capability.consumes}
     * @throws IllegalArgumentException on the first invalid reference
     */
    public static void validate(CapabilitySpec capability, List<ClientAdapter> clientAdapters) {
        if (capability == null || clientAdapters == null) {
            return;
        }

        if (capability.getExposes() != null) {
            for (ServerSpec serverSpec : capability.getExposes()) {
                if (serverSpec instanceof RestServerSpec rest) {
                    validateRest(rest, clientAdapters);
                } else if (serverSpec instanceof McpServerSpec mcp) {
                    validateMcp(mcp, clientAdapters);
                }
            }
        }

        if (capability.getAggregates() != null) {
            for (AggregateSpec aggregate : capability.getAggregates().values()) {
                if (aggregate.getFlows() == null) {
                    continue;
                }
                for (Map.Entry<String, AggregateFlowSpec> flow : aggregate.getFlows().entrySet()) {
                    String where = "aggregate '" + aggregate.getNamespace() + "' flow '"
                            + flow.getKey() + "'";
                    checkCall(flow.getValue().getCall(), where, clientAdapters);
                    checkSteps(flow.getValue().getSteps(), where, clientAdapters);
                }
            }
        }
    }

    private static void validateRest(RestServerSpec rest, List<ClientAdapter> clientAdapters) {
        if (rest.getResources() == null) {
            return;
        }
        for (RestServerResourceSpec resource : rest.getResources().values()) {
            String resourceLabel = "REST resource '" + resource.getPath() + "' in '"
                    + rest.getNamespace() + "'";
            checkForward(resource.getForward(), resourceLabel, clientAdapters);
            if (resource.getOperations() == null) {
                continue;
            }
            for (RestServerOperationSpec operation : resource.getOperations().values()) {
                String where = "REST operation '" + operation.getMethod() + " "
                        + resource.getPath() + "' in '" + rest.getNamespace() + "'";
                checkCall(operation.getCall(), where, clientAdapters);
                checkSteps(operation.getSteps(), where, clientAdapters);
            }
        }
    }

    private static void validateMcp(McpServerSpec mcp, List<ClientAdapter> clientAdapters) {
        if (mcp.getTools() != null) {
            for (Map.Entry<String, McpServerToolSpec> tool : mcp.getTools().entrySet()) {
                String where = "MCP tool '" + tool.getKey() + "' in '" + mcp.getNamespace() + "'";
                checkCall(tool.getValue().getCall(), where, clientAdapters);
                checkSteps(tool.getValue().getSteps(), where, clientAdapters);
            }
        }
        if (mcp.getResources() != null) {
            for (Map.Entry<String, McpServerResourceSpec> resource : mcp.getResources()
                    .entrySet()) {
                String where = "MCP resource '" + resource.getKey() + "' in '"
                        + mcp.getNamespace() + "'";
                checkCall(resource.getValue().getCall(), where, clientAdapters);
                checkSteps(resource.getValue().getSteps(), where, clientAdapters);
            }
        }
    }

    private static void checkForward(RestServerForwardSpec forward, String where,
            List<ClientAdapter> clientAdapters) {
        if (forward == null) {
            return;
        }
        String target = forward.getTargetNamespace();
        ClientAdapter adapter = adapterFor(target, clientAdapters);
        if (adapter == null) {
            throw new IllegalArgumentException("Forward target namespace '" + target + "' of "
                    + where + " is not a consumed namespace. Forward mode needs a consumed "
                    + "adapter of type 'http'.");
        }
        if (!(adapter instanceof HttpClientAdapter)) {
            throw new IllegalArgumentException("Forward target namespace '" + target + "' of "
                    + where + " is a consumed adapter of type '" + adapter.getSpec().getType()
                    + "'. Forward mode needs a consumed adapter of type 'http'.");
        }
        HttpClientAdapter http = (HttpClientAdapter) adapter;
        if (http.getHttpClientSpec().getAuthentication() instanceof AwsSigV4AuthenticationSpec
                && forward.getTrustedHeaders() != null) {
            for (String header : forward.getTrustedHeaders()) {
                if (header != null
                        && header.trim().toLowerCase(Locale.ROOT).startsWith(AMZ_HEADER_PREFIX)) {
                    throw new IllegalArgumentException("Trusted header '" + header + "' of "
                            + where + " can't be forwarded to '" + target + "', which signs "
                            + "with awsSigV4: an x-amz-* header is part of the signed AWS "
                            + "operation, so a caller could reach objects outside the "
                            + "forwarded prefix. Remove it from trustedHeaders.");
                }
            }
        }
    }

    private static void checkSteps(Map<String, OperationStepSpec> steps, String where,
            List<ClientAdapter> clientAdapters) {
        if (steps == null) {
            return;
        }
        for (OperationStepSpec step : steps.values()) {
            if (step instanceof OperationStepCallSpec callStep) {
                checkReference(callStep.getCall(), where + " step '" + step.getName() + "'",
                        clientAdapters);
            }
        }
    }

    private static void checkCall(ServerCallSpec call, String where,
            List<ClientAdapter> clientAdapters) {
        if (call != null) {
            checkReference(call.getOperation(), where, clientAdapters);
        }
    }

    /** Same parsing as the executor: exactly {@code namespace.operation}, else not checked. */
    private static void checkReference(String reference, String where,
            List<ClientAdapter> clientAdapters) {
        if (reference == null) {
            return;
        }
        String[] tokens = reference.split("\\.");
        if (tokens.length != 2) {
            return;
        }
        ClientAdapter adapter = adapterFor(tokens[0], clientAdapters);
        if (adapter != null && !adapter.declaresOperation(tokens[1])) {
            throw new IllegalArgumentException("Consumed namespace '" + tokens[0]
                    + "' has no operation '" + tokens[1] + "' (called from " + where + ")");
        }
    }

    private static ClientAdapter adapterFor(String namespace, List<ClientAdapter> adapters) {
        if (namespace == null) {
            return null;
        }
        for (ClientAdapter adapter : adapters) {
            if (namespace.equals(adapter.getNamespace())) {
                return adapter;
            }
        }
        return null;
    }
}
