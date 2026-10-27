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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;

/**
 * Compiles every template of the consumed adapters when a capability is loaded or validated, so
 * that a template the engine cannot parse fails at startup and in {@code ikanos validate},
 * instead of on the first call as an opaque error (#849).
 *
 * <p>Checked templates: {@code baseUri} + resource {@code path}, the {@code value} and
 * {@code template} of adapter-, resource- and operation-level input parameters, every string
 * of a request body (a plain string body, string {@code data}, and each string inside structured
 * {@code data}, including form fields and multipart parts), and the authentication values that
 * {@code ConsumedAuthentication} renders for HTTP and MCP adapters (basic and digest username and
 * password, bearer token, API key name and value). Templates are compiled the way the engine
 * renders them ({@link Resolver#templateError}). Messages name the adapter namespace, the
 * operation and the location, plus the kind and line of the parse error, and never echo the
 * template text, which may hold static credentials.</p>
 */
public final class ConsumedTemplateValidator {

    private ConsumedTemplateValidator() {
    }

    /**
     * @param spec the capability spec; {@code null} is a no-op
     * @throws IllegalArgumentException listing every template that cannot be parsed
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid consumed template"
                    + (errors.size() > 1 ? "s" : "") + ": " + String.join("; ", errors));
        }
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per template that cannot be parsed, in declaration order
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
                checkAdapter(http, errors);
                checkAuthentication(http.getAuthentication(), http.getNamespace(), errors);
            } else if (client instanceof McpClientSpec mcp) {
                checkAuthentication(mcp.getAuthentication(), mcp.getNamespace(), errors);
            }
        }
        return errors;
    }

    private static void checkAdapter(HttpClientSpec http, List<String> errors) {
        String ns = http.getNamespace();
        checkParameters(http.getInputParameters(), ns + " adapter", errors);
        if (http.getResources() == null) {
            return;
        }
        for (HttpClientResourceSpec resource : http.getResources().values()) {
            String resourceLabel = ns + " resource '" + resource.getName() + "'";
            String baseUri = http.getBaseUri() == null ? "" : http.getBaseUri();
            check(baseUri + (resource.getPath() == null ? "" : resource.getPath()),
                    resourceLabel + " path", errors);
            checkParameters(resource.getInputParameters(), resourceLabel, errors);
            if (resource.getOperations() == null) {
                continue;
            }
            for (HttpClientOperationSpec operation : resource.getOperations().values()) {
                String where = ns + "." + operation.getName();
                checkParameters(operation.getInputParameters(), where, errors);
                checkBody(operation.getBody(), where + " body", errors);
            }
        }
    }

    private static void checkParameters(List<InputParameterSpec> parameters, String where,
            List<String> errors) {
        if (parameters == null) {
            return;
        }
        for (InputParameterSpec parameter : parameters) {
            String label = where + " input parameter '" + parameter.getName() + "'";
            check(parameter.getValue(), label, errors);
            check(parameter.getTemplate(), label, errors);
        }
    }

    /** Checks the values {@code ConsumedAuthentication} renders as templates on every request. */
    private static void checkAuthentication(AuthenticationSpec authentication, String ns,
            List<String> errors) {
        String where = ns + " authentication";
        if (authentication instanceof BasicAuthenticationSpec basic) {
            check(basic.getUsername(), where + " username", errors);
            check(asString(basic.getPassword()), where + " password", errors);
        } else if (authentication instanceof DigestAuthenticationSpec digest) {
            check(digest.getUsername(), where + " username", errors);
            check(asString(digest.getPassword()), where + " password", errors);
        } else if (authentication instanceof BearerAuthenticationSpec bearer) {
            check(bearer.getToken(), where + " token", errors);
        } else if (authentication instanceof ApiKeyAuthenticationSpec apiKey) {
            check(apiKey.getKey(), where + " key", errors);
            check(apiKey.getValue(), where + " value", errors);
        }
    }

    private static String asString(char[] value) {
        return value == null ? null : new String(value);
    }

    /**
     * Walks a body spec, checking every string it contains. Every value is checked, including a
     * {@code type} key: at the top level it is the body type, which holds no template, and deeper
     * it is an ordinary field of the payload.
     */
    private static void checkBody(Object body, String where, List<String> errors) {
        if (body instanceof String text) {
            check(text, where, errors);
        } else if (body instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                checkBody(value, where, errors);
            }
        } else if (body instanceof Collection<?> items) {
            for (Object item : items) {
                checkBody(item, where, errors);
            }
        }
    }

    private static void check(String template, String where, List<String> errors) {
        String error = Resolver.templateError(template);
        if (error != null) {
            errors.add(where + ": " + error);
        }
    }
}
