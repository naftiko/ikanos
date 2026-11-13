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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.restlet.Context;
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

    /** Dot segments, plain or percent-encoded, in lower case. */
    static final Set<String> DOT_SEGMENTS = Set.of("..", ".", "%2e%2e", ".%2e", "%2e.", "%2e");

    /**
     * The resources and headers already warned about a dropped location, so an upstream that
     * always answers that way does not flood the log: later drops are logged at {@code FINE}.
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

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
     * @param relayChallenges whether to relay the upstream's {@code WWW-Authenticate}
     *        challenges: only when the upstream judged the caller's own credential, that is when
     *        the consumed adapter has no {@code authentication}. A challenge on the adapter's own
     *        credential names an internal realm and tells the caller that credential is broken.
     */
    static void relay(Response from, Response to, List<String> trustedResponseHeaders,
            String upstreamBaseUri, boolean relayChallenges) {
        Request incoming = to.getRequest();
        String upstreamRequestUri = from.getRequest() == null
                || from.getRequest().getResourceRef() == null ? upstreamBaseUri
                        : from.getRequest().getResourceRef().toString();

        String location = from.getHeaders().getFirstValue(HeaderConstants.HEADER_LOCATION, true);
        String relayedLocation = location == null ? null
                : relayedLocation(location, upstreamRequestUri, upstreamBaseUri, incoming);
        if (location != null && relayedLocation == null) {
            logDropped(HeaderConstants.HEADER_LOCATION, from, resourceKey(incoming,
                    upstreamBaseUri));
        }
        to.setLocationRef(verbatim(relayedLocation));

        Representation entity = to.getEntity();
        if (entity != null) {
            String contentLocation = from.getHeaders()
                    .getFirstValue(HeaderConstants.HEADER_CONTENT_LOCATION, true);
            if (contentLocation != null || entity.getLocationRef() != null) {
                String relayedContentLocation = contentLocation == null ? null
                        : relayedLocation(contentLocation, upstreamRequestUri, upstreamBaseUri,
                                incoming);
                if (contentLocation != null && relayedContentLocation == null) {
                    logDropped(HeaderConstants.HEADER_CONTENT_LOCATION, from,
                            resourceKey(incoming, upstreamBaseUri));
                }
                entity.setLocationRef(verbatim(relayedContentLocation));
            }
        }

        to.setRetryAfter(from.getRetryAfter());
        to.setCacheDirectives(new ArrayList<>(from.getCacheDirectives()));
        to.getDimensions().addAll(from.getDimensions());
        if (variesOnUnknownField(from)) {
            to.getDimensions().add(Dimension.UNSPECIFIED);
        }
        to.getAllowedMethods().addAll(from.getAllowedMethods());
        if (relayChallenges) {
            to.setChallengeRequests(rawChallenges(from));
        }
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
     * @return a key naming the exposed resource of {@code incoming} and its forward target
     */
    private static String resourceKey(Request incoming, String upstreamBaseUri) {
        return exposedPath(incoming) + " -> " + upstreamBaseUri;
    }

    /**
     * Logs that a location header was not relayed, naming the header and the upstream status but
     * never the value, which can carry the upstream host. The first redirect or {@code 201}
     * without its location for a given resource and header is worth a warning; anything else is
     * logged at {@code FINE}.
     */
    static void logDropped(String headerName, Response from, String resourceKey) {
        int status = from.getStatus() == null ? 0 : from.getStatus().getCode();
        boolean warnable = (status >= 300 && status < 400) || status == 201;
        Level level = warnable && WARNED.add(resourceKey + " " + headerName) ? Level.WARNING
                : Level.FINE;
        Context.getCurrentLogger().log(level, () -> "Forward mode did not relay the upstream "
                + headerName + " header of a " + status + " response: it points at the "
                + "upstream outside the forward target's baseUri, or cannot be parsed.");
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
     * {@code Content-Location}. The value is first resolved against the upstream request URI and
     * normalized (so {@code ..} segments are applied).
     *
     * <ul>
     * <li>Same scheme, host and port as {@code upstreamBaseUri}, and under its path: the matching
     * path-absolute reference under the exposed resource, such as {@code /proxy/items/42}, so
     * neither the upstream host nor the caller's {@code Host} is written.</li>
     * <li>Any other value on the upstream host, whatever its form (relative, scheme-relative,
     * another scheme or port, outside the base path): dropped, so the upstream host, and paths
     * that would point into the gateway's own namespace, never reach the caller.</li>
     * <li>A value on another host: relayed unchanged.</li>
     * <li>A value or base URI that cannot be parsed: dropped.</li>
     * </ul>
     *
     * <p>Scheme and host are compared case-insensitively, a default port is ignored, a trailing
     * slash on the base URI is ignored, and only whole path segments match. The host and port are
     * read from the raw authority, so a host name with an underscore (a common Docker or
     * Kubernetes service name) is compared like any other.
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
        if (base == null || value == null || host(base) == null) {
            return null;
        }
        URI requestUri = parse(upstreamRequestUri);
        URI resolved = (requestUri != null && requestUri.isAbsolute() ? requestUri : base)
                .resolve(value).normalize();
        String host = host(resolved);
        if (host == null || !host.equalsIgnoreCase(host(base))) {
            return raw;
        }
        String basePath = stripTrailingSlashes(base.getRawPath());
        String path = resolved.getRawPath() == null ? "" : resolved.getRawPath();
        if (hasDotSegment(path)) {
            return null;
        }
        boolean under = path.equals(basePath) || (path.startsWith(basePath)
                && (basePath.isEmpty() || path.charAt(basePath.length()) == '/'));
        if (!under || !resolved.getScheme().equalsIgnoreCase(base.getScheme())
                || port(resolved) != port(base)) {
            return null;
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
     * @return {@code true} when a raw path segment is a dot segment, plain or percent-encoded
     *         ({@code ..}, {@code .}, {@code %2e%2e}, {@code .%2e}, {@code %2e.}, {@code %2e}, in
     *         any case). {@link URI#normalize()} keeps a leading {@code ..} on an absolute path
     *         and never decodes {@code %2e}, while WHATWG URL parsers apply both, so such a path
     *         could leave the base once the caller follows it.
     */
    static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (DOT_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    /**
     * @return the host of {@code uri}, read from its raw authority (without user info and port),
     *         or {@code null} when it has none. {@link URI#getHost()} returns {@code null} for a
     *         host name with an underscore, which {@link URI} parses as a registry-based
     *         authority.
     */
    static String host(URI uri) {
        String hostPort = hostPort(uri);
        if (hostPort == null) {
            return null;
        }
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end < 0 ? null : hostPort.substring(0, end + 1);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        return host.isEmpty() ? null : host;
    }

    /**
     * @return the port of {@code uri}, read from its raw authority, else the default port of its
     *         scheme (443 for {@code https}, 80 otherwise), or -1 when the port is not a number
     */
    static int port(URI uri) {
        String hostPort = hostPort(uri);
        if (hostPort != null) {
            int colon = hostPort.lastIndexOf(':');
            if (colon > hostPort.lastIndexOf(']') && colon < hostPort.length() - 1) {
                try {
                    return Integer.parseInt(hostPort.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String hostPort(URI uri) {
        String authority = uri.getRawAuthority();
        return authority == null ? null : authority.substring(authority.lastIndexOf('@') + 1);
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
