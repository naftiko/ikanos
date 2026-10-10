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
package io.ikanos.engine.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.ikanos.spec.CapabilitySpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.util.BindingSpec;

/**
 * Checks that every namespace is declared once across the whole capability, not only within one
 * section.
 *
 * <p>{@link ImportResolver} checks each section on its own. This check runs once after all
 * sections are resolved, so imported entries are seen under their effective namespace (the
 * {@code as} alias, or the {@code import} name). It covers the same lists as the Polychro rule
 * {@code ikanos-namespaces-unique} plus {@code capability.aggregates}: root and capability
 * {@code consumes}, {@code capability.exposes}, {@code capability.aggregates}, and root and
 * capability {@code binds}.</p>
 *
 * <p>The check matters most for {@code call: namespace.name}, which resolves against both
 * consumed adapters and aggregate flows: a namespace declared in both would make the target
 * ambiguous. Entries without a namespace (for example the control adapter) are exempt.</p>
 */
public final class NamespaceUniquenessValidator {

    private NamespaceUniquenessValidator() {
    }

    /**
     * Validate namespace uniqueness across sections.
     *
     * @param spec the resolved spec
     * @throws IllegalArgumentException naming both locations when a namespace is declared twice
     */
    public static void validate(IkanosSpec spec) {
        if (spec == null) {
            return;
        }
        Map<String, String> seen = new LinkedHashMap<>();

        addClients(seen, spec.getConsumes(), "consumes");
        addBinds(seen, spec.getBinds(), "binds");

        CapabilitySpec capability = spec.getCapability();
        if (capability != null) {
            addClients(seen, capability.getConsumes(), "capability.consumes");
            List<ServerSpec> exposes = capability.getExposes();
            if (exposes != null) {
                for (int i = 0; i < exposes.size(); i++) {
                    ServerSpec server = exposes.get(i);
                    add(seen, server != null ? server.getNamespace() : null,
                            "capability.exposes[" + i + "]");
                }
            }
            Map<String, AggregateSpec> aggregates = capability.getAggregates();
            if (aggregates != null) {
                for (AggregateSpec aggregate : aggregates.values()) {
                    add(seen, aggregate != null ? aggregate.getNamespace() : null,
                            "capability.aggregates");
                }
            }
            addBinds(seen, capability.getBinds(), "capability.binds");
        }
    }

    private static void addClients(Map<String, String> seen, List<ClientSpec> clients,
            String section) {
        if (clients == null) {
            return;
        }
        for (int i = 0; i < clients.size(); i++) {
            ClientSpec client = clients.get(i);
            add(seen, client != null ? client.getNamespace() : null,
                    section + "[" + i + "]");
        }
    }

    private static void addBinds(Map<String, String> seen, List<BindingSpec> binds,
            String section) {
        if (binds == null) {
            return;
        }
        for (int i = 0; i < binds.size(); i++) {
            BindingSpec bind = binds.get(i);
            add(seen, bind != null ? bind.getNamespace() : null, section + "[" + i + "]");
        }
    }

    private static void add(Map<String, String> seen, String namespace, String location) {
        if (namespace == null || namespace.isEmpty()) {
            return;
        }
        String first = seen.putIfAbsent(namespace, location);
        if (first != null) {
            throw new IllegalArgumentException("Namespace '" + namespace
                    + "' is declared more than once (" + first + " and " + location
                    + "). Namespaces must be unique across consumes, exposes, aggregates "
                    + "and binds.");
        }
    }
}
