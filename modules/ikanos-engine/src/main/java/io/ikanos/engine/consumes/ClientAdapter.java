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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.samskivert.mustache.Mustache;

import io.ikanos.Capability;
import io.ikanos.engine.Adapter;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Client Adapter implementation.
 *
 * <p>Every consumed adapter, whatever its protocol, is reached from steps and exposers through
 * {@link #prepare(String, Map)}, which returns a protocol-neutral {@link ConsumedInvocation}. See
 * {@code design-docs/consumed-invocation-carrier.md}.</p>
 *
 * <h2>Thread safety</h2>
 * The {@code capability} and {@code spec} references are held in {@link AtomicReference}s so
 * that a future Control-port "hot reload" feature can replace them atomically while request
 * threads read them. This satisfies SonarQube rule {@code java:S3077}.
 */
public abstract class ClientAdapter extends Adapter {

    private final AtomicReference<Capability> capability = new AtomicReference<>();

    private final AtomicReference<ClientSpec> spec = new AtomicReference<>();

    public ClientAdapter(Capability capability, ClientSpec spec) {
        this.capability.set(capability);
        this.spec.set(spec);
    }

    /**
     * Resolve a consumed URI (an HTTP {@code baseUri} or an MCP {@code endpoint}) against the
     * capability bindings, once, when the adapter is built. Call parameters are never used, so a
     * caller cannot choose the upstream host (SSRF). Bound values are substituted as written,
     * without URL encoding, since a binding may hold a whole URL. A value without placeholders is
     * returned unchanged.
     *
     * @param namespace the consumes namespace, for error messages
     * @param field the field name, for error messages ({@code baseUri}, {@code endpoint})
     * @param uri the URI as written, may be null
     * @param bindings the capability bindings, may be null
     * @return the resolved URI
     * @throws IllegalArgumentException when a placeholder is not bound, or when the resolved value
     *     is not an absolute http(s) URI with a host. The resolved value is not echoed, as it may
     *     come from a secret store.
     */
    protected static String resolveUriFromBindings(String namespace, String field, String uri,
            Map<String, Object> bindings) {
        if (uri == null || !uri.contains("{{")) {
            return uri;
        }
        String resolved = resolveFromBindings(namespace, field, uri, bindings);
        try {
            URI parsed = new URI(resolved);
            String scheme = parsed.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || parsed.getHost() == null || parsed.getHost().isEmpty()) {
                throw new IllegalArgumentException("consumes '" + namespace + "' " + field
                        + " must resolve to an absolute http(s) URI with a host");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("consumes '" + namespace + "' " + field
                    + " does not resolve to a valid URI");
        }
        return resolved;
    }

    /**
     * Resolve a consumed connection value (a URI, a JDBC URL, a database user or password)
     * against the capability bindings, once, when the adapter is built. Call parameters are never
     * used, so a caller cannot choose the upstream or its credentials. A value without
     * placeholders is returned unchanged.
     *
     * @param namespace the consumes namespace, for error messages
     * @param field the field name, for error messages
     * @param value the value as written, may be null
     * @param bindings the capability bindings, may be null
     * @return the resolved value
     * @throws IllegalArgumentException when the template is malformed, a placeholder is not
     *     bound or names a whole bind namespace. The resolved value is not
     *     echoed, as it may come from a secret store.
     */
    protected static String resolveFromBindings(String namespace, String field, String value,
            Map<String, Object> bindings) {
        if (value == null || !value.contains("{{")) {
            return value;
        }
        // Parsed as rendering parses it, so a section ({{#PORT}}:{{PORT}}{{/PORT}}) or an
        // unescaped tag ({{&HOST}}) is accepted. The resolved value is never searched: a bound
        // secret may itself contain "{{".
        String error = Resolver.templateError(value);
        if (error == null && hasUnclosedTag(value)) {
            error = "unclosed tag";
        }
        if (error != null) {
            throw new IllegalArgumentException("consumes '" + namespace + "' " + field
                    + " is not a valid template: " + error);
        }
        Map<String, Object> scope = bindings != null ? bindings : Map.of();
        // Looked up as Mustache does, so {{secrets.PORT}} is found under its namespace (#942).
        // A variable inside a section is optional, as when it renders.
        List<String> missing = Resolver.unresolvedVariables(value, scope);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("consumes '" + namespace + "' " + field
                    + " references unbound variables " + missing
                    + ". Declare them in a binds entry.");
        }
        // A whole bind namespace would render every value it holds, as in {{secrets}} (#1064)
        List<String> namespaces = Resolver.namespaceVariables(value, scope);
        if (!namespaces.isEmpty()) {
            throw new IllegalArgumentException("consumes '" + namespace + "' " + field + " "
                    + Resolver.describeNamespaceVariables(namespaces));
        }
        return Resolver.resolveMustacheTemplate(value, scope);
    }

    /**
     * JMustache keeps an unclosed tag, such as {@code {{DB_PASSWORD}, as literal text, so it
     * compiles. A "{{" left in the template's text, sections included, is such a tag.
     */
    private static boolean hasUnclosedTag(String template) {
        boolean[] found = new boolean[1];
        Resolver.compileTemplate(template).visit(new Mustache.Visitor() {
            @Override
            public void visitText(String text) {
                found[0] |= text.contains("{{");
            }

            @Override
            public void visitVariable(String name) {
                // a closed tag
            }

            @Override
            public boolean visitInclude(String name) {
                return false;
            }

            @Override
            public boolean visitSection(String name) {
                return true;
            }

            @Override
            public boolean visitInvertedSection(String name) {
                return true;
            }
        });
        return found[0];
    }

    public Capability getCapability() {
        return capability.get();
    }

    public void setCapability(Capability capability) {
        this.capability.set(capability);
    }

    public ClientSpec getSpec() {
        return spec.get();
    }

    public void setSpec(ClientSpec spec) {
        this.spec.set(spec);
    }

    /** @return the namespace declared on this adapter's spec, or {@code null} */
    public String getNamespace() {
        ClientSpec current = spec.get();
        return current != null ? current.getNamespace() : null;
    }

    /**
     * Build an invocation for one declared operation. Nothing is sent until
     * {@link ConsumedInvocation#invoke()}.
     *
     * @param operationName the operation name as declared under this adapter
     * @param parameters    resolved parameters available for template substitution
     * @return the invocation, or {@code null} when this adapter declares no such operation
     * @throws IllegalArgumentException when templates cannot be resolved
     */
    public abstract ConsumedInvocation prepare(String operationName,
            Map<String, Object> parameters);

    /**
     * Whether this adapter declares an operation with the given name. Used at load time to
     * reject a {@code call:} that names an operation the consumed namespace does not declare,
     * instead of failing on the first request.
     *
     * <p>The default returns {@code true}: an adapter that cannot enumerate its operations is
     * not checked at load time, and an unknown operation still fails in
     * {@link #prepare(String, Map)}.</p>
     *
     * @param operationName the operation name as written after the namespace in {@code call:}
     * @return {@code false} only when the adapter knows it declares no such operation
     */
    public boolean declaresOperation(String operationName) {
        return true;
    }

}
