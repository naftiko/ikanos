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

import java.util.ArrayList;
import java.util.List;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

/**
 * Checks the {@code queryParameters} lists of the consumed HTTP resources and operations when a
 * capability is loaded or validated (#987): a name MUST NOT also be declared in the
 * {@code inputParameters} of the same resource or operation, and MUST NOT be listed twice.
 *
 * <p>The schema already rejects a repeated name ({@code uniqueItems}) in {@code ikanos validate};
 * this check also covers a capability loaded without schema validation and an imported adapter.
 * A name that a resource lists and one of its operations declares again is not an error: as for
 * any resource-level parameter, the operation's declaration replaces it (#909).</p>
 */
public final class ConsumedQueryParameterValidator {

    private ConsumedQueryParameterValidator() {
    }

    /**
     * @param spec the capability spec; {@code null} is a no-op
     * @throws IllegalArgumentException listing every conflicting or repeated name
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid consumed query parameter"
                    + (errors.size() > 1 ? "s" : "") + ": " + String.join("; ", errors));
        }
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per conflicting or repeated name, in declaration order
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return errors;
        }
        List<ClientSpec> clients = new ArrayList<>();
        if (spec.getConsumes() != null) {
            clients.addAll(spec.getConsumes());
        }
        if (spec.getCapability() != null && spec.getCapability().getConsumes() != null) {
            clients.addAll(spec.getCapability().getConsumes());
        }
        for (ClientSpec client : clients) {
            if (client instanceof HttpClientSpec http && http.getResources() != null) {
                checkAdapter(http, errors);
            }
        }
        return errors;
    }

    private static void checkAdapter(HttpClientSpec http, List<String> errors) {
        String ns = http.getNamespace();
        for (HttpClientResourceSpec resource : http.getResources().values()) {
            errors.addAll(resource.queryParameterErrors(
                    ns + " resource '" + resource.getName() + "'"));
            if (resource.getOperations() == null) {
                continue;
            }
            for (HttpClientOperationSpec operation : resource.getOperations().values()) {
                errors.addAll(operation.queryParameterErrors(
                        ns + "." + operation.getName()));
            }
        }
    }
}
