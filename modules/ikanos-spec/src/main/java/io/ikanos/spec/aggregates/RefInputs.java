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
package io.ikanos.spec.aggregates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.exposes.rest.RestPaths;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;

/**
 * The inputs of an exposed unit that uses {@code ref}: its own, or its aggregate flow's when it
 * declares none (#1039). MCP tools and REST operations follow the same rule, so a flow exposed
 * through both adapters accepts the same inputs on each.
 *
 * <p>
 * A flow input has no {@code in}, so a REST operation places the inputs it inherits: an input
 * named by a placeholder of the resource path ({@code {{name}}} or {@code {name}}, see
 * {@link RestPaths}) is read from the path, the others from the query string for {@code GET},
 * {@code DELETE} and {@code HEAD}, and from the members of the JSON body for the other methods.
 */
public final class RefInputs {

    /** Methods whose inherited inputs are read from the query string rather than the body. */
    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE", "HEAD");

    private RefInputs() {}

    /**
     * The inputs of a unit: its own when it declares any, otherwise its flow's.
     *
     * @param own the unit's own inputs, may be null
     * @param flowInputs supplies the flow's inputs; called only when the unit declares none and
     *        uses {@code ref}, may return null
     * @param ref the unit's {@code ref}, or null when it uses none
     * @return the inputs, never null
     */
    public static List<InputParameterSpec> ownOrFlow(List<InputParameterSpec> own, String ref,
            Supplier<? extends Collection<InputParameterSpec>> flowInputs) {
        if (own != null && !own.isEmpty()) {
            return own;
        }
        if (ref == null) {
            return List.of();
        }
        Collection<InputParameterSpec> inherited = flowInputs.get();
        return inherited != null ? List.copyOf(inherited) : List.of();
    }

    /**
     * The flow inputs a REST operation that uses {@code ref} accepts in addition to the adapter's
     * own inputs, placed in the request. Empty when the operation does not use {@code ref}, or
     * when it or its resource declares inputs: own inputs replace the flow's.
     *
     * @param resource the operation's resource
     * @param operation the operation
     * @param flowInputs supplies the flow's inputs; called only when the operation inherits them,
     *        may return null
     * @return copies of the flow's inputs with their {@code in} set, never null
     */
    public static List<InputParameterSpec> forRestOperation(RestServerResourceSpec resource,
            RestServerOperationSpec operation,
            Supplier<? extends Collection<InputParameterSpec>> flowInputs) {
        if (operation == null || operation.getRef() == null) {
            return List.of();
        }
        if (declaresInputs(operation.getInputParameters())
                || (resource != null && declaresInputs(resource.getInputParameters()))) {
            return List.of();
        }
        Collection<InputParameterSpec> inherited = flowInputs.get();
        if (inherited == null || inherited.isEmpty()) {
            return List.of();
        }
        Set<String> pathNames =
                RestPaths.placeholders(resource != null ? resource.getPath() : null);
        List<InputParameterSpec> placed = new ArrayList<>(inherited.size());
        for (InputParameterSpec input : inherited) {
            InputParameterSpec copy = input.copy();
            if (pathNames.contains(input.getName())) {
                copy.setIn("path");
                // A path parameter is always present: OpenAPI requires it to say so.
                copy.setDeclaredRequired(true);
            } else {
                copy.setIn(placementOutsidePath(operation.getMethod()));
                // A flow input is optional unless it says otherwise, on REST as on MCP.
                copy.setDeclaredRequired(input.isRequired());
                if ("body".equals(copy.getIn()) && copy.getValue() == null) {
                    // The member of that name only: an absent member stays absent instead of
                    // falling back to the whole body.
                    copy.setValue("$['" + input.getName() + "']");
                }
            }
            placed.add(copy);
        }
        return placed;
    }

    /**
     * Where an inherited input that is not in the path goes for a given method.
     *
     * @param method the HTTP method, {@code GET} when null as in the schema
     * @return {@code query} for {@code GET}, {@code DELETE} and {@code HEAD}, {@code body}
     *         otherwise
     */
    static String placementOutsidePath(String method) {
        String verb = method != null ? method.trim().toUpperCase(Locale.ROOT) : "GET";
        return QUERY_METHODS.contains(verb) ? "query" : "body";
    }

    private static boolean declaresInputs(List<InputParameterSpec> inputs) {
        return inputs != null && !inputs.isEmpty();
    }
}
