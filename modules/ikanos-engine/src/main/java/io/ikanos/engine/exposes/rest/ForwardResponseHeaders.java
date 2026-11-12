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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Dimension;
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
            "cache-control", "vary", "allow", "www-authenticate", "accept-ranges", "age",
            "content-type", "content-encoding", "content-language", "content-location",
            "content-disposition", "content-range", "content-md5", "etag", "last-modified",
            "expires");

    /**
     * The other headers Restlet models as properties: its server connector refuses them as raw
     * headers, and the engine does not relay them (the server sets its own {@code Server},
     * {@code Date} and {@code Via}, {@code Warning} is obsolete (RFC 9111), CORS belongs to the
     * exposed adapter, and the rest are request headers).
     */
    static final Set<String> NOT_RELAYABLE = Set.of("server", "date", "via", "warning",
            "set-cookie2", "authentication-info", "access-control-allow-credentials",
            "access-control-allow-headers", "access-control-allow-methods",
            "access-control-allow-origin", "access-control-expose-headers",
            "access-control-max-age", "access-control-request-headers",
            "access-control-request-method", "accept", "accept-charset", "accept-encoding",
            "accept-language", "accept-patch", "authorization", "cookie", "expect", "from", "host",
            "if-match", "if-modified-since", "if-none-match", "if-range", "if-unmodified-since",
            "max-forwards", "range", "referer", "user-agent");

    /** {@code Vary} field names that Restlet maps to a {@link Dimension}. */
    static final Set<String> VARY_DIMENSIONS = Set.of("accept", "accept-charset",
            "accept-encoding", "accept-language", "authorization", "user-agent", "origin", "*");

    private ForwardResponseHeaders() {}

    /**
     * Checks a {@code trustedResponseHeaders} list when the capability loads.
     *
     * @param names the listed header names, or {@code null}
     * @param where the resource the list belongs to, for the error message
     * @throws IllegalArgumentException when an entry is blank or names a header that can never be
     *         relayed
     */
    public static void validate(List<String> names, String where) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("trustedResponseHeaders of " + where
                        + " has a blank entry. List header names only.");
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
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
        String upstreamRequestUri = from.getRequest() == null
                || from.getRequest().getResourceRef() == null ? upstreamBaseUri
                        : from.getRequest().getResourceRef().toString();

        String location = from.getHeaders().getFirstValue(HeaderConstants.HEADER_LOCATION, true);
        to.setLocationRef(location == null ? null
                : verbatim(relayedLocation(location, upstreamRequestUri, upstreamBaseUri,
                        incoming)));

        Representation entity = to.getEntity();
        if (entity != null) {
            String contentLocation = from.getHeaders()
                    .getFirstValue(HeaderConstants.HEADER_CONTENT_LOCATION, true);
            if (contentLocation != null || entity.getLocationRef() != null) {
                entity.setLocationRef(contentLocation == null ? null
                        : verbatim(relayedLocation(contentLocation, upstreamRequestUri,
                                upstreamBaseUri, incoming)));
            }
        }

        to.setRetryAfter(from.getRetryAfter());
        to.setCacheDirectives(new ArrayList<>(from.getCacheDirectives()));
        to.getDimensions().addAll(from.getDimensions());
        if (variesOnUnknownField(from)) {
            to.getDimensions().add(Dimension.UNSPECIFIED);
        }
        to.getAllowedMethods().addAll(from.getAllowedMethods());
        to.setChallengeRequests(rawChallenges(from));
        to.getServerInfo().setAcceptingRanges(from.getServerInfo().isAcceptingRanges());
        to.setAge(from.getAge());

        if (trustedResponseHeaders == null) {
            return;
        }
        Set<String> listed = new LinkedHashSet<>();
        for (String name : trustedResponseHeaders) {
            if (name != null && !name.isBlank()) {
                listed.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String key : listed) {
            if (SET_COOKIE.equals(key)) {
                to.getCookieSettings().addAll(from.getCookieSettings());
            } else if (!RELAYED_BY_DEFAULT.contains(key) && !HOP_BY_HOP.contains(key)
                    && !NOT_RELAYABLE.contains(key)) {
                for (Header header : from.getHeaders()) {
                    if (header.getName().equalsIgnoreCase(key)) {
                        to.getHeaders().add(header.getName(), header.getValue());
                    }
                }
            }
        }
    }

    /**
     * @return {@code true} when a {@code Vary} field name of the upstream answer has no Restlet
     *         {@link Dimension}, so the relayed answer must vary on everything ({@code Vary: *})
     */
    static boolean variesOnUnknownField(Response from) {
        for (Header header : from.getHeaders()) {
            if (!header.getName().equalsIgnoreCase(HeaderConstants.HEADER_VARY)) {
                continue;
            }
            for (String field : header.getValue().split(",")) {
                String key = field.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && !VARY_DIMENSIONS.contains(key)) {
                    return true;
                }
            }
        }
        return false;
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
            // Restlet writes "<scheme> <raw value>" and drops the challenge when the raw value
            // adds nothing, so a bare challenge (e.g. "Negotiate") keeps a single space, which
            // HTTP treats as optional whitespace around the field value.
            challenge.setRawValue(space < 0 ? " " : value.substring(space + 1).trim());
            challenges.add(challenge);
        }
        return challenges;
    }

    /**
     * Decides what to send the caller for an upstream {@code Location} or
     * {@code Content-Location}.
     *
     * <ul>
     * <li>Under {@code upstreamBaseUri} (once resolved against the upstream request URI): the
     * matching path-absolute reference under the exposed resource, such as
     * {@code /proxy/items/42}, so neither the upstream host nor the caller's {@code Host} is
     * written.</li>
     * <li>Absolute and on the upstream's authority, but outside the base URI: dropped, so the
     * upstream host does not reach the caller.</li>
     * <li>Otherwise: the raw value, unchanged.</li>
     * </ul>
     *
     * <p>The comparison lowercases the scheme and the host, ignores a default port and a trailing
     * slash on the base URI, and only matches whole path segments.
     *
     * @param raw the raw header value from the upstream
     * @param upstreamRequestUri the URI the upstream request was sent to
     * @param upstreamBaseUri the resolved base URI of the forward target
     * @param incoming the incoming request, carrying the forwarded {@code path} attribute
     * @return the value to send, or {@code null} to drop the header
     */
    static String relayedLocation(String raw, String upstreamRequestUri, String upstreamBaseUri,
            Request incoming) {
        URI base = parse(upstreamBaseUri);
        URI value = parse(raw.trim());
        if (base == null || value == null) {
            return null;
        }
        URI resolved = value;
        if (!value.isAbsolute()) {
            URI requestUri = parse(upstreamRequestUri);
            if (requestUri == null) {
                return raw;
            }
            resolved = requestUri.resolve(value);
        }
        if (!sameAuthority(resolved, base)) {
            return raw;
        }
        String basePath = stripTrailingSlashes(base.getRawPath());
        String path = resolved.getRawPath() == null ? "" : resolved.getRawPath();
        boolean under = path.equals(basePath) || (path.startsWith(basePath)
                && (basePath.isEmpty() || path.charAt(basePath.length()) == '/'));
        if (!under) {
            return value.isAbsolute() ? null : raw;
        }
        String exposed = exposedPath(incoming);
        if (exposed == null) {
            return null;
        }
        String rewritten = exposed + path.substring(basePath.length());
        if (rewritten.isEmpty()) {
            rewritten = "/";
        }
        if (resolved.getRawQuery() != null) {
            rewritten += "?" + resolved.getRawQuery();
        }
        if (resolved.getRawFragment() != null) {
            rewritten += "#" + resolved.getRawFragment();
        }
        return rewritten;
    }

    /**
     * @return the path of the incoming request without its forwarded {@code {{path}}} part and
     *         any trailing slash, or {@code null} when the path does not end with that part
     */
    static String exposedPath(Request incoming) {
        String path = incoming.getResourceRef().getPath();
        if (path == null) {
            return null;
        }
        Object forwarded = incoming.getAttributes().get("path");
        String forwardedPath = forwarded == null ? "" : forwarded.toString();
        if (!forwardedPath.isEmpty()) {
            if (!path.endsWith("/" + forwardedPath)) {
                return null;
            }
            path = path.substring(0, path.length() - forwardedPath.length() - 1);
        }
        return stripTrailingSlashes(path);
    }

    private static boolean sameAuthority(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String stripTrailingSlashes(String path) {
        String result = path == null ? "" : path;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static URI parse(String uri) {
        try {
            return uri == null ? null : new URI(uri);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * @return a reference Restlet writes exactly as given, or {@code null} when {@code value} is
     *         {@code null}. Restlet 3.0.0-m1 writes {@code Location} and {@code Content-Location}
     *         from {@link Reference#getTargetRef()}, which throws for a relative reference with no
     *         base; a path-absolute location needs no base.
     */
    static Reference verbatim(String value) {
        return value == null ? null : new VerbatimReference(value);
    }

    /** A reference that is its own target, so a relative value is written as is. */
    static final class VerbatimReference extends Reference {
        VerbatimReference(String value) {
            super(value);
        }

        @Override
        public Reference getTargetRef() {
            return this;
        }
    }
}
