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
package io.ikanos.engine.exposes.mcp;

import java.util.ArrayList;
import java.util.List;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;

/**
 * Infers the {@code call} of an MCP tool from its name (#988).
 *
 * <p>An exposed MCP tool that declares none of {@code call}, {@code steps}, {@code ref} or mock
 * {@code outputParameters} calls the consumed operation with the same name as the tool. The
 * candidates are the operations of the consumed HTTP adapters and the tools of the consumed MCP
 * adapters, which a {@code call:} reaches the same way. Exactly one consumed namespace must have
 * one: with none or several, the capability is rejected with a message naming the tool, and the
 * author writes {@code call:} explicitly. Aggregate flows are not candidates, since an exposed
 * tool reaches a flow through {@code ref:}.</p>
 *
 * <p>{@link #resolve(IkanosSpec)} runs at load time, after imports are resolved, and sets the
 * inferred {@code call} on the tool. Everything downstream (the reference checks, the tool
 * handler, tracing) then sees the same thing as an explicit {@code call:}; the tool's
 * {@code with} and mapped {@code outputParameters} apply to it unchanged.
 * {@link #findErrors(IkanosSpec)} reports the same errors without changing the spec, for
 * {@code ikanos validate}.</p>
 */
public final class InferredToolCallResolver {

    private InferredToolCallResolver() {
    }

    /**
     * Set the inferred {@code call} on every MCP tool that has none.
     *
     * @param spec the spec, with its imports resolved; {@code null} is a no-op
     * @throws IllegalArgumentException when a tool's call cannot be inferred
     */
    public static void resolve(IkanosSpec spec) {
        List<String> errors = inferAll(spec, true, false);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    /**
     * The tools whose call cannot be inferred, without changing the spec. Imports are not
     * resolved here: when a consumes entry is an import, a tool without a candidate is not
     * reported, since its operation may live in the imported file. The capability is checked in
     * full when it loads.
     *
     * @param spec the spec as read from its file; {@code null} has no errors
     * @return one message per tool, empty when every call can be inferred
     */
    public static List<String> findErrors(IkanosSpec spec) {
        return inferAll(spec, false, true);
    }

    /**
     * Whether a tool needs an inferred call: no {@code call}, {@code steps}, {@code ref} or mock
     * {@code outputParameters}.
     */
    static boolean needsInferredCall(McpServerToolSpec tool) {
        return tool.getCall() == null && tool.getRef() == null
                && (tool.getSteps() == null || tool.getSteps().isEmpty())
                && !tool.hasMockOutputParameters();
    }

    /**
     * The consumed namespaces that have an operation (HTTP) or a tool (MCP) named
     * {@code name}, in declaration order.
     */
    static List<String> candidateNamespaces(List<ClientSpec> consumes, String name) {
        List<String> namespaces = new ArrayList<>();
        if (consumes == null || name == null) {
            return namespaces;
        }
        for (ClientSpec client : consumes) {
            if (declares(client, name)) {
                namespaces.add(client.getNamespace());
            }
        }
        return namespaces;
    }

    private static boolean declares(ClientSpec client, String name) {
        if (client instanceof HttpClientSpec http) {
            // Same lookup as HttpClientAdapter.getOperationSpec: by operation name, any resource.
            for (HttpClientResourceSpec resource : http.getResources().values()) {
                for (HttpClientOperationSpec operation : resource.getOperations().values()) {
                    if (name.equals(operation.getName())) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (client instanceof McpClientSpec mcp) {
            return mcp.getTools().containsKey(name);
        }
        return false;
    }

    private static List<String> inferAll(IkanosSpec spec, boolean apply,
            boolean tolerateImports) {
        List<String> errors = new ArrayList<>();
        CapabilitySpec capability = spec != null ? spec.getCapability() : null;
        if (capability == null || capability.getExposes() == null) {
            return errors;
        }
        List<ClientSpec> consumes = capability.getConsumes();
        boolean hasImports = tolerateImports && consumes != null
                && consumes.stream().anyMatch(ImportedConsumesHttpSpec.class::isInstance);

        for (ServerSpec server : capability.getExposes()) {
            if (!(server instanceof McpServerSpec mcp) || mcp.getTools() == null) {
                continue;
            }
            for (McpServerToolSpec tool : mcp.getTools().values()) {
                // A tool without a name is not exposed (McpServerAdapter skips it): nothing to infer.
                if (tool == null || tool.getName() == null || tool.getName().isBlank()
                        || !needsInferredCall(tool)) {
                    continue;
                }
                String name = tool.getName();
                List<String> namespaces = candidateNamespaces(consumes, name);
                if (namespaces.size() == 1) {
                    if (apply) {
                        tool.setCall(new ServerCallSpec(namespaces.get(0) + "." + name));
                    }
                } else if (!namespaces.isEmpty() || !hasImports) {
                    errors.add(message(mcp.getNamespace(), name, namespaces));
                }
            }
        }
        return errors;
    }

    static String message(String serverNamespace, String tool, List<String> namespaces) {
        String prefix = "MCP tool '" + tool + "' in '" + serverNamespace
                + "' declares no 'call', 'steps', 'ref' or mock 'outputParameters', so it calls"
                + " the consumed operation named '" + tool + "'";
        if (namespaces.isEmpty()) {
            return prefix + ", but no consumed namespace has one. Add 'call: <namespace>."
                    + "<operation>' to the tool.";
        }
        List<String> calls = namespaces.stream().map(ns -> "'call: " + ns + "." + tool + "'")
                .toList();
        return prefix + ", but several consumed namespaces have one: "
                + String.join(", ", namespaces.stream().map(ns -> "'" + ns + "'").toList())
                + ". Add " + String.join(" or ", calls) + " to the tool.";
    }
}
