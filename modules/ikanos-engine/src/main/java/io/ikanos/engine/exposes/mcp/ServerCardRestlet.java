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
package io.ikanos.engine.exposes.mcp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CacheDirective;
import org.restlet.data.CharacterSet;
import org.restlet.data.Dimension;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import org.restlet.representation.StringRepresentation;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.ServerCard;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.Template;

/**
 * Serves the bytes of an MCP Server Card (SEP-2127): {@code GET} answers 200, or 304 when
 * {@code If-None-Match} matches its strong {@code ETag}; {@code HEAD} answers the same without the
 * body; {@code OPTIONS} answers the CORS preflight; any other method answers 405. Every answer
 * carries the CORS headers the discovery document requires, so a browser client on any origin can
 * read the card.
 *
 * <p>Without {@code card.url}, the card's remote URL is built from the request: its scheme and
 * {@code Host}, or, from a peer the adapter trusts ({@code trust: forwarded}),
 * {@code X-Forwarded-Proto} and {@code X-Forwarded-Host}. The {@code ETag} follows that URL. A
 * request without a usable host gets a 400 rather than a card naming the bind address. The host is
 * part of every cache's key, but a forwarded header is not, and Restlet cannot name it in
 * {@code Vary}, so an answer to a trusted peer carries {@code Vary: *}: it is revalidated with its
 * {@code ETag} rather than reused as is.</p>
 *
 * <p>A public card answers with {@code Cache-Control: public, max-age=3600}. A card behind the
 * server's authentication answers with {@code private} instead, and its preflight allows the
 * credentials header.</p>
 */
public class ServerCardRestlet extends Restlet {

    static final MediaType CARD_MEDIA_TYPE = MediaType.register(ServerCardBuilder.MEDIA_TYPE,
            "MCP Server Card");

    /** Seconds a client or cache may reuse the card. */
    static final int MAX_AGE_SECONDS = 3600;

    static final String FORWARDED_HOST = "X-Forwarded-Host";
    static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    /**
     * A URI authority without user info: a DNS name or IPv4 address, or a bracketed IPv6 literal,
     * with an optional port.
     */
    static final Pattern AUTHORITY = Pattern.compile("^(\\[[0-9A-Fa-f:.]+\\]"
            + "|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*"
            + "[A-Za-z0-9])?)*\\.?)(?::[0-9]{1,5})?$");

    /** Longest host accepted, as a DNS name is at most 253 characters, plus a port. */
    static final int AUTHORITY_MAX = 260;

    private static final Set<Method> ALLOWED = Set.of(Method.GET, Method.HEAD, Method.OPTIONS);

    private final Template template;
    private final boolean authenticated;
    private final Set<String> allowedHeaders;
    private final Predicate<Request> trustsForwardedHeaders;

    /** The card last rendered and the URL origin it was rendered for. */
    private final AtomicReference<Rendered> last = new AtomicReference<>();

    private record Rendered(String origin, ServerCard card, Tag etag) {}

    /**
     * @param credentialsHeader the header a client authenticates with, allowed in preflights when
     *        the card is authenticated; {@code null} for a public card
     * @param trustsForwardedHeaders whether a request comes from a peer whose
     *        {@code X-Forwarded-Host} and {@code X-Forwarded-Proto} are trusted
     */
    public ServerCardRestlet(Context context, Template template, boolean authenticated,
            String credentialsHeader, Predicate<Request> trustsForwardedHeaders) {
        super(context);
        this.template = template;
        this.authenticated = authenticated;
        this.trustsForwardedHeaders = trustsForwardedHeaders;
        Set<String> headers = new LinkedHashSet<>(List.of("Content-Type", "If-None-Match"));
        if (authenticated && credentialsHeader != null) {
            headers.add(credentialsHeader);
        }
        this.allowedHeaders = Set.copyOf(headers);
    }

    @Override
    public void handle(Request request, Response response) {
        super.handle(request, response);
        response.setAccessControlAllowOrigin("*");
        response.setAccessControlAllowMethods(Set.of(Method.GET));
        response.setAccessControlAllowHeaders(allowedHeaders);
        response.setAccessControlExposeHeaders(Set.of("ETag"));

        Method method = request.getMethod();
        if (Method.OPTIONS.equals(method)) {
            response.setAllowedMethods(ALLOWED);
            response.setStatus(Status.SUCCESS_NO_CONTENT);
            return;
        }
        if (!Method.GET.equals(method) && !Method.HEAD.equals(method)) {
            response.setAllowedMethods(ALLOWED);
            response.setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            return;
        }

        Rendered card = render(request);
        if (card == null) {
            response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "No usable host for the card URL");
            return;
        }
        if (!template.isFixed() && trustsForwardedHeaders.test(request)) {
            response.getDimensions().add(Dimension.UNSPECIFIED);
        }
        StringRepresentation entity = new StringRepresentation(card.card().json(), CARD_MEDIA_TYPE);
        entity.setCharacterSet(CharacterSet.UTF_8);
        entity.setTag(card.etag());
        List<CacheDirective> cache = response.getCacheDirectives();
        cache.add(authenticated ? CacheDirective.privateInfo() : CacheDirective.publicInfo());
        cache.add(CacheDirective.maxAge(MAX_AGE_SECONDS));

        if (matches(card.etag(), request.getConditions().getNoneMatch())) {
            response.setStatus(Status.REDIRECTION_NOT_MODIFIED);
            entity.setText((String) null);
            response.setEntity(entity);
            return;
        }
        response.setStatus(Status.SUCCESS_OK);
        response.setEntity(entity);
    }

    /** The card for this request, or {@code null} when its URL cannot be built from it. */
    private Rendered render(Request request) {
        String origin = template.isFixed() ? null : origin(request);
        if (!template.isFixed() && origin == null) {
            return null;
        }
        Rendered cached = last.get();
        if (cached != null && Objects.equals(cached.origin(), origin)) {
            return cached;
        }
        ServerCard card = template.render(origin);
        Rendered rendered = new Rendered(origin, card, new Tag(card.etag(), false));
        last.set(rendered);
        return rendered;
    }

    /**
     * {@code <scheme>://<host>[:<port>]} as the client addressed the server, or {@code null} when
     * the request names no valid host. The forwarded headers count only from a trusted peer; one
     * that is present but unusable gives {@code null}, never a fallback to the {@code Host} the
     * gateway reached the engine with.
     */
    String origin(Request request) {
        String scheme = request.isConfidential() ? "https" : "http";
        String host = header(request, "Host");
        if (trustsForwardedHeaders.test(request)) {
            String proto = header(request, FORWARDED_PROTO);
            if (proto != null) {
                proto = proto.toLowerCase(Locale.ROOT);
                if (!"http".equals(proto) && !"https".equals(proto)) {
                    return null;
                }
                scheme = proto;
            }
            String forwardedHost = header(request, FORWARDED_HOST);
            if (forwardedHost != null) {
                host = forwardedHost;
            }
        }
        if (host == null || host.length() > AUTHORITY_MAX || !AUTHORITY.matcher(host).matches()) {
            return null;
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT);
    }

    /** The first element of a header's value, trimmed, or {@code null} when absent or empty. */
    static String header(Request request, String name) {
        String value = request.getHeaders().getFirstValue(name, true);
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = (comma >= 0 ? value.substring(0, comma) : value).trim();
        return first.isEmpty() ? null : first;
    }

    /** Whether an {@code If-None-Match} list names this card: {@code *} or a weak match. */
    static boolean matches(Tag etag, List<Tag> noneMatch) {
        if (noneMatch == null) {
            return false;
        }
        for (Tag tag : noneMatch) {
            if (Tag.ALL.equals(tag) || etag.weakMatch(tag)) {
                return true;
            }
        }
        return false;
    }

    /** The card template served. */
    Template getTemplate() {
        return template;
    }
}
