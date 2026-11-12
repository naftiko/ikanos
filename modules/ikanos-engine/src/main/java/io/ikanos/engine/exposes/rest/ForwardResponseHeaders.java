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
package io.ikanos.engine.exposes.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Header;
import org.restlet.data.Reference;
import org.restlet.engine.header.HeaderConstants;
import org.restlet.representation.Representation;

/**
 * Relays an upstream response's headers to the caller in REST forward mode (#1002).
 *
 * <p>Restlet parses the standard response headers of the upstream answer into typed properties
 * of the client {@link Response}, and its server connector refuses a standard header added as a
 * raw header. So each standard header is relayed by copying its property, and only extension
 * headers are copied as raw headers. The entity, and with it its headers ({@code Content-Type},
 * {@code Content-Disposition}, {@code ETag}, {@code Last-Modified}, {@code Expires}, ...), is
 * relayed by the caller of {@link #relay}.
 */
public final class ForwardResponseHeaders {

    /** Opt-in header: never relayed unless listed in {@code trustedResponseHeaders}. */
    static final String SET_COOKIE = "set-cookie";

    /**
     * Headers that only make sense on one connection (RFC 9110 section 7.6.1), and
     * {@code Content-Length}, which the server connector computes. Never relayed.
     */
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-authenticate",
            "proxy-authorization", "content-length");

    /** Standard headers relayed by default: response properties and entity headers. */
    static final Set<String> RELAYED_BY_DEFAULT = Set.of("location", "retry-after",
            "cache-control", "vary", "allow", "www-authenticate", "accept-ranges", "age", "warning",
            "content-type", "content-encoding", "content-language", "content-location",
            "content-disposition", "content-range", "content-md5", "etag", "last-modified",
            "expires");

    /**
     * The other headers Restlet models as properties: its server connector refuses them as raw
     * headers, and the engine does not relay them (the server sets its own {@code Server},
     * {@code Date} and {@code Via}, CORS belongs to the exposed adapter, and the rest are request
     * headers).
     */
    static final Set<String> NOT_RELAYABLE = Set.of("server", "date", "via", "set-cookie2",
            "authentication-info", "access-control-allow-credentials",
            "access-control-allow-headers", "access-control-allow-methods",
            "access-control-allow-origin", "access-control-expose-headers",
            "access-control-max-age", "access-control-request-headers",
            "access-control-request-method", "accept", "accept-charset", "accept-encoding",
            "accept-language", "accept-patch", "authorization", "cookie", "expect", "from", "host",
            "if-match", "if-modified-since", "if-none-match", "if-range", "if-unmodified-since",
            "max-forwards", "range", "referer", "user-agent");

    private ForwardResponseHeaders() {}

    /**
     * Checks a {@code trustedResponseHeaders} list when the capability loads.
     *
     * @param names the listed header names, or {@code null}
     * @param where the resource the list belongs to, for the error message
     * @throws IllegalArgumentException when a listed header can never be relayed
     */
    public static void validate(List<String> names, String where) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(key)) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " lists '" + name + "', a hop-by-hop header that forward mode never "
                        + "relays.");
            }
            if (NOT_RELAYABLE.contains(key)) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " lists '" + name + "', a standard header that forward mode cannot "
                        + "relay.");
            }
        }
    }

    /**
     * Copies the headers of an upstream response to the response sent to the caller. The entity
     * must already be set on {@code to}.
     *
     * @param from the upstream response
     * @param to the response to the caller, whose request is the incoming request
     * @param trustedResponseHeaders the extension headers and opt-in headers to relay
     * @param upstreamBaseUri the resolved base URI of the forward target
     */
    static void relay(Response from, Response to, List<String> trustedResponseHeaders,
            String upstreamBaseUri) {
        Request incoming = to.getRequest();

        if (from.getLocationRef() != null) {
            to.setLocationRef(new Reference(rewriteLocation(
                    from.getLocationRef().getTargetRef().toString(), upstreamBaseUri, incoming)));
        }
        Representation entity = to.getEntity();
        if (entity != null && entity.getLocationRef() != null
                && entity.getLocationRef().isAbsolute()) {
            entity.setLocationRef(rewriteLocation(entity.getLocationRef().toString(),
                    upstreamBaseUri, incoming));
        }
        to.setRetryAfter(from.getRetryAfter());
        to.setCacheDirectives(new ArrayList<>(from.getCacheDirectives()));
        to.getDimensions().addAll(from.getDimensions());
        to.getAllowedMethods().addAll(from.getAllowedMethods());
        to.setChallengeRequests(rawChallenges(from));
        to.getServerInfo().setAcceptingRanges(from.getServerInfo().isAcceptingRanges());
        to.setAge(from.getAge());
        to.setWarnings(new ArrayList<>(from.getWarnings()));

        if (trustedResponseHeaders == null) {
            return;
        }
        for (String name : trustedResponseHeaders) {
            String key = name.toLowerCase(Locale.ROOT);
            if (SET_COOKIE.equals(key)) {
                to.getCookieSettings().addAll(from.getCookieSettings());
            } else if (!RELAYED_BY_DEFAULT.contains(key) && !HOP_BY_HOP.contains(key)
                    && !NOT_RELAYABLE.contains(key)) {
                for (Header header : from.getHeaders()) {
                    if (header.getName().equalsIgnoreCase(name)) {
                        to.getHeaders().add(name, header.getValue());
                    }
                }
            }
        }
    }

    /**
     * Rebuilds the upstream's {@code WWW-Authenticate} challenges from the raw header values, one
     * challenge per header line, so they are written back as received. Restlet re-formats a
     * parsed challenge, which drops the quotes of a token-valued {@code realm} that RFC 7235
     * section 2.2 requires.
     */
    static List<ChallengeRequest> rawChallenges(Response from) {
        List<ChallengeRequest> challenges = new ArrayList<>();
        for (Header header : from.getHeaders()) {
            if (!header.getName().equalsIgnoreCase(HeaderConstants.HEADER_WWW_AUTHENTICATE)) {
                continue;
            }
            String value = header.getValue().trim();
            int space = value.indexOf(' ');
            String scheme = space < 0 ? value : value.substring(0, space);
            if (scheme.isEmpty()) {
                continue;
            }
            ChallengeRequest challenge =
                    new ChallengeRequest(new ChallengeScheme("HTTP_" + scheme, scheme));
            if (space >= 0) {
                challenge.setRawValue(value.substring(space + 1).trim());
            }
            challenges.add(challenge);
        }
        return challenges;
    }

    /**
     * Rewrites an upstream location under {@code upstreamBaseUri} to the matching location under
     * the exposed resource, so the caller is not sent to the upstream host. The exposed base is
     * the incoming request URI without its forwarded {@code {{path}}} part.
     *
     * @param location the absolute upstream location
     * @param upstreamBaseUri the resolved base URI of the forward target
     * @param incoming the incoming request, carrying the forwarded {@code path} attribute
     * @return the rewritten location, or {@code location} when it is not under the base URI or
     *         the exposed base cannot be derived
     */
    static String rewriteLocation(String location, String upstreamBaseUri, Request incoming) {
        if (location == null || upstreamBaseUri == null || !location.startsWith(upstreamBaseUri)) {
            return location;
        }
        String rest = location.substring(upstreamBaseUri.length());
        if (!rest.isEmpty() && "/?#".indexOf(rest.charAt(0)) < 0) {
            return location;
        }
        String exposedBase = exposedBase(incoming);
        return exposedBase == null ? location : exposedBase + rest;
    }

    /**
     * @return the incoming request URI up to, and without, its forwarded {@code {{path}}} part and
     *         any trailing slash, or {@code null} when the path does not end with that part
     */
    static String exposedBase(Request incoming) {
        Reference ref = incoming.getResourceRef();
        String path = ref.getPath();
        Object forwarded = incoming.getAttributes().get("path");
        String forwardedPath = forwarded == null ? "" : forwarded.toString();
        if (path == null) {
            return null;
        }
        if (!forwardedPath.isEmpty()) {
            if (!path.endsWith("/" + forwardedPath)) {
                return null;
            }
            path = path.substring(0, path.length() - forwardedPath.length() - 1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return ref.getHostIdentifier() + path;
    }
}
