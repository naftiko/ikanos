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
package io.ikanos.engine.consumes.http;

import java.util.ArrayList;
import java.util.List;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

/**
 * Refuses a static value of a consumed {@code in: header} input that the engine can never send,
 * such as {@code If-Match: v1} (an unquoted entity tag) or {@code Range: items=0-9} (#1001). Such a
 * value used to fail or be dropped on every call; it now fails the capability at load time and
 * {@code ikanos validate}. A value that holds a {@code {{…}}} template is only known per call and
 * is checked then. Messages name the adapter, the operation and the header, never the value.
 */
public final class ConsumedHeaderValidator {

    private ConsumedHeaderValidator() {
    }

    /**
     * @param spec the capability spec; {@code null} is a no-op
     * @throws IllegalArgumentException listing every static header value that cannot be sent
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per static header value that cannot be sent, in declaration order
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
            if (client instanceof HttpClientSpec http) {
                String ns = http.getNamespace();
                check(http.getInputParameters(), ns + " adapter", errors);
                if (http.getResources() == null) {
                    continue;
                }
                for (HttpClientResourceSpec resource : http.getResources().values()) {
                    check(resource.getInputParameters(),
                            ns + " resource '" + resource.getName() + "'", errors);
                    if (resource.getOperations() == null) {
                        continue;
                    }
                    for (HttpClientOperationSpec operation : resource.getOperations().values()) {
                        check(operation.getInputParameters(), ns + "." + operation.getName(),
                                errors);
                    }
                }
            }
        }
        return errors;
    }

    private static void check(List<InputParameterSpec> parameters, String where,
            List<String> errors) {
        if (parameters == null) {
            return;
        }
        for (InputParameterSpec parameter : parameters) {
            String value = parameter.getValue();
            if (!"header".equalsIgnoreCase(parameter.getIn()) || value == null
                    || value.contains("{{")) {
                continue;
            }
            String name = parameter.getWireName() != null && !parameter.getWireName().isBlank()
                    ? parameter.getWireName() : parameter.getName();
            if (!StandardRequestHeaders.isSetThroughProperty(name)) {
                continue;
            }
            try {
                StandardRequestHeaders.set(new Request(Method.GET, "http://localhost/"), name,
                        value);
            } catch (IllegalArgumentException e) {
                errors.add(where + " header input '" + name + "' has a value the engine cannot "
                        + "send: " + e.getMessage());
            }
        }
    }
}
