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
import java.util.Set;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CacheDirective;
import org.restlet.data.CharacterSet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import org.restlet.representation.StringRepresentation;
import io.ikanos.engine.exposes.mcp.ServerCardBuilder.ServerCard;

/**
 * Serves the bytes of an MCP Server Card (SEP-2127): {@code GET} answers 200, or 304 when
 * {@code If-None-Match} matches its strong {@code ETag}; {@code OPTIONS} answers the CORS
 * preflight; any other method answers 405. Every answer carries the CORS headers the discovery
 * document requires, so a browser client on any origin can read the card.
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

    private static final Set<Method> ALLOWED = Set.of(Method.GET, Method.OPTIONS);

    private final ServerCard card;
    private final Tag etag;
    private final boolean authenticated;
    private final Set<String> allowedHeaders;

    /**
     * @param credentialsHeader the header a client authenticates with, allowed in preflights when
     *        the card is authenticated; {@code null} for a public card
     */
    public ServerCardRestlet(Context context, ServerCard card, boolean authenticated,
            String credentialsHeader) {
        super(context);
        this.card = card;
        this.etag = new Tag(card.etag(), false);
        this.authenticated = authenticated;
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
        if (!Method.GET.equals(method)) {
            response.setAllowedMethods(ALLOWED);
            response.setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            return;
        }

        StringRepresentation entity = new StringRepresentation(card.json(), CARD_MEDIA_TYPE);
        entity.setCharacterSet(CharacterSet.UTF_8);
        entity.setTag(etag);
        List<CacheDirective> cache = response.getCacheDirectives();
        cache.add(authenticated ? CacheDirective.privateInfo() : CacheDirective.publicInfo());
        cache.add(CacheDirective.maxAge(MAX_AGE_SECONDS));

        if (matches(request.getConditions().getNoneMatch())) {
            response.setStatus(Status.REDIRECTION_NOT_MODIFIED);
            entity.setText((String) null);
            response.setEntity(entity);
            return;
        }
        response.setStatus(Status.SUCCESS_OK);
        response.setEntity(entity);
    }

    /** Whether an {@code If-None-Match} list names this card: {@code *} or a weak match. */
    boolean matches(List<Tag> noneMatch) {
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

    /** The card served. */
    ServerCard getCard() {
        return card;
    }
}
