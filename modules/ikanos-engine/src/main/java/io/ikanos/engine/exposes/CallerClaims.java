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
package io.ikanos.engine.exposes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.restlet.Request;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.Resolver;

/**
 * The caller's validated identity, as templates read it: {@code {{auth.claims.sub}}},
 * {@code {{auth.claims.roles}}} (#998).
 *
 * <p>Built once per request, where the request enters the adapter, from the claims of the OAuth 2.1
 * token that {@link OAuth2AuthenticationRestlet} validated. Never from anything the client sends:
 * a client value can never stand in for the claims ({@link Resolver#requireCallerValues}).</p>
 *
 * <p>Each claim value is reduced to text a consumed API can receive in a header, a query or a
 * body: a string, number or boolean as is; a date ({@code exp}, {@code iat}) as seconds since the
 * epoch; an array of them joined with commas; an object as a
 * nested map, so {@code {{auth.claims.realm_access.roles}}} works. A value outside
 * {@link IdentityValues} (too long, or with characters that could split a header or a list) is
 * left out, so a template that reads it fails the call ({@code CallerClaimMissingException})
 * instead of sending it.</p>
 */
public final class CallerClaims {

    /** Key of the claims map under {@link Resolver#CALLER_ROOT}. */
    public static final String CLAIMS = "claims";

    /**
     * The caller root of the MCP request being dispatched on this thread. The MCP transport sets
     * it around the dispatch, since the protocol handlers only see the JSON-RPC message; it is read
     * once, before any upstream call. It is this class's own: Restlet's current request is replaced
     * by the first outbound call on the thread.
     */
    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();

    private CallerClaims() {}

    /**
     * @return the caller root of a request ({@code {claims: {...}}}), or {@code null} when the
     *         request carries no validated OAuth 2.1 token
     */
    public static Map<String, Object> of(Request request) {
        Object claims = request == null ? null
                : request.getAttributes().get(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE);
        if (!(claims instanceof JWTClaimsSet validated)) {
            return null;
        }
        return new CallerRoot(Map.of(CLAIMS, view(validated.getClaims())));
    }

    /**
     * Puts the caller root into template parameters, after the request inputs: unless an input
     * already uses the name {@code auth}, in which case the input is kept (as before #998) and
     * {@code {{auth.claims...}}} templates fail rather than read it. Dotted keys starting with
     * {@code auth.claims}, which a template would read before the root, are removed.
     *
     * @param parameters the parameters templates are rendered with, changed in place
     * @param root the caller root from {@link #of}, or {@code null}
     */
    public static void putInto(Map<String, Object> parameters, Map<String, Object> root) {
        parameters.keySet().removeIf(key -> key != null
                && key.startsWith(Resolver.CALLER_CLAIMS));
        if (root != null && !parameters.containsKey(Resolver.CALLER_ROOT)) {
            parameters.put(Resolver.CALLER_ROOT, root);
        }
    }

    /**
     * Runs {@code work} with {@code root} as the caller root of this thread (MCP over HTTP), and
     * clears it afterwards.
     */
    public static <T> T runAs(Map<String, Object> root, Supplier<T> work) {
        Map<String, Object> previous = CURRENT.get();
        CURRENT.set(root);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** @return the caller root set by {@link #runAs} on this thread, or {@code null}. */
    public static Map<String, Object> current() {
        return CURRENT.get();
    }

    /**
     * The text of one claim, for an identity header. A name that is a claim of its own (such as
     * Auth0's {@code https://example.com/roles}) is read as is; otherwise a dotted name is a path
     * into nested claims, such as {@code realm_access.roles}.
     *
     * @param root the caller root from {@link #of}, may be {@code null}
     * @return the claim text, or {@code null} when the token does not carry a usable value
     */
    public static String claim(Map<String, Object> root, String name) {
        if (root == null || !(root.get(CLAIMS) instanceof Map<?, ?> claims) || name == null) {
            return null;
        }
        Object value = claims.get(name);
        if (value == null && name.contains(".")) {
            Object current = claims;
            for (String segment : name.split("\\.", -1)) {
                current = current instanceof Map<?, ?> map ? map.get(segment) : null;
            }
            value = current;
        }
        return value instanceof String text ? text
                : value instanceof Boolean flag ? flag.toString() : null;
    }

    /** The claims as templates read them: see the class description. */
    static Map<String, Object> view(Map<String, Object> claims) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (claims != null) {
            claims.forEach((name, value) -> {
                Object text = text(value);
                if (text != null) {
                    view.put(name, text);
                }
            });
        }
        return Collections.unmodifiableMap(view);
    }

    /** @return the text of a claim value, a nested view, or {@code null} to leave it out */
    @SuppressWarnings("unchecked")
    private static Object text(Object value) {
        if (value instanceof java.util.Date date) {
            // exp, iat, nbf and auth_time: seconds since the epoch, as in the token
            return String.valueOf(date.getTime() / 1000);
        }
        if (value instanceof Boolean) {
            // kept a boolean, so a section over a false claim does not render
            return value;
        }
        if (value instanceof String || value instanceof Number) {
            return IdentityValues.sanitize(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> nested) {
            return view((Map<String, Object>) nested);
        }
        if (value instanceof Collection<?> values) {
            List<String> elements = new ArrayList<>();
            for (Object element : values) {
                boolean scalar = element instanceof String || element instanceof Number
                        || element instanceof Boolean;
                String text = scalar ? IdentityValues.sanitize(String.valueOf(element)) : null;
                if (text == null) {
                    return null;
                }
                elements.add(text);
            }
            String joined = String.join(",", elements);
            return elements.isEmpty() || joined.length() > IdentityValues.MAX_LENGTH ? null : joined;
        }
        return null;
    }
}
