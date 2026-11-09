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

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;

import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.observability.OtelNullSafety;
import io.ikanos.engine.observability.OtelRestletBridge;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.engine.util.Converter;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientPaginationSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A call to a consumed HTTP operation that pages: one call drains the whole list, page after
 * page, within its bounds (HTTP Pagination design doc).
 *
 * <p>Each page is fetched from the checkpoint of the page before it, and yields a record: its
 * items and the next checkpoint, or the response of a page that failed. The checkpoint is opaque
 * to clients and enough on its own to fetch the next page: {@code page} counts the pages fetched,
 * {@code items} the items collected, and the style's position says where the next page is.</p>
 *
 * <p>The result is the concatenation of each page's {@code items} array, in page order, as one
 * JSON response; a page that fails returns its own response, so the call fails as an unpaged one
 * does. Reaching {@code maxPages} or {@code maxItems}, a page whose {@code items} is not an array,
 * a repeated position or a next link to another origin fail the call with a
 * {@link PaginationException}.</p>
 */
public class PaginatedHttpInvocation extends HttpInvocation {

    private static final ObjectMapper JSON = IkanosMappers.json();

    /** One link of an RFC 8288 {@code Link} header: its target and its parameters. */
    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;[^,<]*)*)");
    private static final Pattern REL_NEXT =
            Pattern.compile(";\\s*rel\\s*=\\s*\"?([^\";]*)\"?", Pattern.CASE_INSENSITIVE);

    /** Thrown when a drain cannot go on: a bound, a loop, a page of the wrong shape. */
    public static final class PaginationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        PaginationException(String message) {
            super(message);
        }
    }

    private final HttpClientPaginationSpec pagination;
    private final Map<String, Object> parameters;
    private final String label;

    PaginatedHttpInvocation(HttpClientAdapter adapter, HttpClientOperationSpec operationSpec,
            Request firstRequest, HttpClientPaginationSpec pagination,
            Map<String, Object> parameters) {
        super(adapter, operationSpec, firstRequest);
        this.pagination = pagination;
        this.parameters = parameters;
        this.label = adapter.getHttpClientSpec().getNamespace() + "." + operationSpec.getName();
    }

    /** The resolved pagination this call follows. */
    public HttpClientPaginationSpec pagination() {
        return pagination;
    }

    @Override
    protected ConsumedResult doInvoke() {
        ArrayNode items = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        ObjectNode checkpoint = null;
        while (true) {
            ObjectNode page = fetch(checkpoint);
            if (page.path("failed").asBoolean()) {
                return failedPage(page);
            }
            ObjectNode next = accept(page, items, seen);
            if (next == null) {
                return drained(items);
            }
            checkpoint = next;
        }
    }

    /**
     * Adds a fetched page's items to {@code items}, checks the bounds and that the position is
     * new, and returns the checkpoint to fetch the next page from, or {@code null} at the end.
     */
    ObjectNode accept(ObjectNode page, ArrayNode items, Set<String> seen) {
        items.addAll((ArrayNode) page.get("items"));
        if (items.size() > pagination.maxItemsOrDefault()) {
            throw new PaginationException("Paginated call '" + label + "' collected more than "
                    + "maxItems (" + pagination.maxItemsOrDefault() + ") items; raise maxItems "
                    + "to drain the whole list");
        }
        JsonNode next = page.get("checkpoint");
        if (next == null || next.isNull()) {
            return null;
        }
        if (next.path("page").asInt() >= pagination.maxPagesOrDefault()) {
            throw new PaginationException("Paginated call '" + label + "' reached maxPages ("
                    + pagination.maxPagesOrDefault() + ") with pages left; raise maxPages to "
                    + "drain the whole list");
        }
        if (!seen.add(position(next))) {
            throw new PaginationException("Paginated call '" + label + "' was sent back to a "
                    + "position it already fetched: the upstream loops");
        }
        return (ObjectNode) next;
    }

    /** The style's position in a checkpoint, as a string a repeated one is detected by. */
    static String position(JsonNode checkpoint) {
        return checkpoint.has("cursor") ? "cursor:" + checkpoint.get("cursor").asString()
                : "next:" + checkpoint.path("next").toString();
    }

    /**
     * Fetches the page after {@code checkpoint}, the first when it is {@code null}, and returns
     * its record: {@code items} and {@code checkpoint} (null at the end), or {@code failed} with
     * the page's {@code status}, {@code mediaType} and {@code body}.
     */
    ObjectNode fetch(ObjectNode checkpoint) {
        Request request = requestFor(checkpoint);
        OtelRestletBridge.injectContext(request);
        Response response = new Response(request);
        getAdapter().getHttpClient().handle(request, response);
        ObjectNode record = JSON.createObjectNode();
        String body = text(response);
        Status status = response.getStatus();
        if (status == null || !status.isSuccess()) {
            record.put("failed", true);
            record.put("status", status != null ? status.getCode() : 0);
            Representation entity = response.getEntity();
            if (entity != null && entity.getMediaType() != null) {
                record.put("mediaType", entity.getMediaType().getName());
            }
            if (body != null) {
                record.put("body", body);
            }
            return record;
        }
        JsonNode document;
        try {
            document = body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            throw new PaginationException("A page of paginated call '" + label
                    + "' is not JSON");
        }
        JsonNode found = document == null ? null
                : Converter.jsonPathExtract(document, pagination.getItems());
        if (found == null || !found.isArray()) {
            throw new PaginationException("A page of paginated call '" + label + "' has no "
                    + "array at its items path " + pagination.getItems());
        }
        record.set("items", found);
        int pageNumber = checkpoint == null ? 1 : checkpoint.path("page").asInt() + 1;
        int collected = (checkpoint == null ? 0 : checkpoint.path("items").asInt())
                + found.size();
        ObjectNode next = nextCheckpoint(checkpoint, request, response, document, found.size());
        if (next != null) {
            next.put("page", pageNumber);
            next.put("items", collected);
        }
        record.set("checkpoint", next);
        return record;
    }

    /** The checkpoint of the page after this one, or {@code null} when this one is the last. */
    private ObjectNode nextCheckpoint(ObjectNode checkpoint, Request request, Response response,
            JsonNode document, int received) {
        ObjectNode next = JSON.createObjectNode();
        next.put("style", pagination.getStyle());
        switch (pagination.getStyle()) {
            case "cursor" -> {
                String token = read(pagination.getCursor().getFrom(), response, document);
                if (token == null || token.isEmpty()) {
                    return null;
                }
                next.put("cursor", token);
            }
            case "nextLink" -> {
                String link = nextLink(response, document);
                if (link == null || link.isEmpty()) {
                    return null;
                }
                next.put("next", sameOrigin(new Reference(request.getResourceRef(), link)
                        .getTargetRef().toString()));
            }
            case "page" -> {
                int current = checkpoint == null ? start() : checkpoint.path("next").asInt();
                Integer total = number(read(pagination.getPage().getTotal(), response, document));
                if (lastPage(received) || (total != null && current >= start() + total - 1)) {
                    return null;
                }
                next.put("next", current + 1);
            }
            case "offset" -> {
                int current = checkpoint == null ? 0 : checkpoint.path("next").asInt();
                Integer total =
                        number(read(pagination.getOffset().getTotal(), response, document));
                int following = current + received;
                if (lastPage(received) || (total != null && following >= total)) {
                    return null;
                }
                next.put("next", following);
            }
            default -> throw new PaginationException("Unknown pagination style "
                    + pagination.getStyle());
        }
        return next;
    }

    private boolean lastPage(int received) {
        return received == 0 || received < pagination.getPageSize().getValue();
    }

    private int start() {
        Integer start = pagination.getPage().getStart();
        return start != null ? start : 1;
    }

    /** The request of the page after {@code checkpoint}: a fresh one, authenticated afresh. */
    Request requestFor(ObjectNode checkpoint) {
        // The first page is the operation's own request; each next one is built afresh.
        Request request = checkpoint == null
                ? getRequest()
                : getAdapter().buildRequest(getOperationSpec(), parameters);
        String style = pagination.getStyle();
        if ("nextLink".equals(style) && checkpoint != null) {
            String link = checkpoint.path("next").asString();
            request.setResourceRef(new Reference(link));
            // The link is followed as is; credentials come from the adapter, never the URL.
            getAdapter().setChallengeResponse(null, request, link, parameters);
            return request;
        }
        if (pagination.getPageSize() != null && pagination.getPageSize().getParam() != null
                && pagination.getPageSize().getValue() != null) {
            setQuery(request, pagination.getPageSize().getParam(),
                    String.valueOf(pagination.getPageSize().getValue()));
        }
        switch (style) {
            case "cursor" -> {
                if (checkpoint != null) {
                    String token = checkpoint.path("cursor").asString();
                    if (pagination.getCursor().getHeader() != null) {
                        request.getHeaders().set(pagination.getCursor().getHeader(), token);
                    } else {
                        setQuery(request, pagination.getCursor().getParam(), token);
                    }
                }
            }
            case "page" -> setQuery(request, pagination.getPage().getParam(), String.valueOf(
                    checkpoint == null ? start() : checkpoint.path("next").asInt()));
            case "offset" -> setQuery(request, pagination.getOffset().getParam(), String.valueOf(
                    checkpoint == null ? 0 : checkpoint.path("next").asInt()));
            default -> {
                // nextLink: the first page is the operation's own request
            }
        }
        return request;
    }

    private static void setQuery(Request request, String name, String value) {
        Reference reference = request.getResourceRef();
        Form query = reference.getQueryAsForm();
        query.removeAll(name);
        query.add(name, value);
        reference.setQuery(query.getQueryString());
    }

    /** Reads a value from a JSONPath into the body, or from {@code {header: name}}. */
    private static String read(Object from, Response response, JsonNode document) {
        if (from == null) {
            return null;
        }
        if (from instanceof Map<?, ?> source && source.get("header") != null) {
            return response.getHeaders().getFirstValue(String.valueOf(source.get("header")),
                    true);
        }
        if (document == null) {
            return null;
        }
        JsonNode value = Converter.jsonPathExtract(document, String.valueOf(from));
        return value == null || value.isNull() || value.isMissingNode() ? null
                : value.isString() ? value.asString() : value.toString();
    }

    private static Integer number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The next page's link: from a {@code Link} header's {@code rel="next"}, or the body. */
    private String nextLink(Response response, JsonNode document) {
        Object from = pagination.getNextLink().getFrom();
        if (from instanceof Map<?, ?> source && source.get("header") != null) {
            String[] values = response.getHeaders()
                    .getValuesArray(String.valueOf(source.get("header")), true);
            for (String value : values) {
                Matcher link = LINK.matcher(value);
                while (link.find()) {
                    Matcher rel = REL_NEXT.matcher(link.group(2));
                    while (rel.find()) {
                        for (String type : rel.group(1).trim().split("\\s+")) {
                            if ("next".equals(type.toLowerCase(Locale.ROOT))) {
                                return link.group(1).trim();
                            }
                        }
                    }
                }
            }
            return null;
        }
        return read(from, response, document);
    }

    /** Refuses a next link to another origin: an upstream must not steer the credentials. */
    private String sameOrigin(String link) {
        URI target = URI.create(link);
        URI base = URI.create(getAdapter().getBaseUri());
        if (!base.getScheme().equalsIgnoreCase(String.valueOf(target.getScheme()))
                || !base.getHost().equalsIgnoreCase(String.valueOf(target.getHost()))
                || port(base) != port(target)) {
            throw new PaginationException("Paginated call '" + label + "' got a next link to "
                    + "another origin than its baseUri; it is not followed");
        }
        return link;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String text(Response response) {
        Representation entity = response.getEntity();
        if (entity == null) {
            return null;
        }
        try {
            return entity.getText();
        } catch (java.io.IOException e) {
            throw new PaginationException("A page could not be read: " + e.getMessage());
        }
    }

    /** The drained list, as the one JSON response of the call. */
    ConsumedResult drained(ArrayNode items) {
        Response response = new Response(getRequest());
        response.setStatus(Status.SUCCESS_OK);
        response.setEntity(new StringRepresentation(items.toString(),
                MediaType.APPLICATION_JSON));
        return new HttpConsumedResult(getOperationSpec(), response);
    }

    /** The response of the page that failed, as the call's own. */
    ConsumedResult failedPage(ObjectNode page) {
        Response response = new Response(getRequest());
        response.setStatus(Status.valueOf(page.path("status").asInt()));
        if (page.has("body")) {
            String mediaType = page.hasNonNull("mediaType")
                    ? page.get("mediaType").asString() : null;
            response.setEntity(new StringRepresentation(page.get("body").asString(),
                    mediaType != null ? MediaType.valueOf(mediaType) : MediaType.TEXT_PLAIN));
        }
        return new HttpConsumedResult(getOperationSpec(), response);
    }

    @Override
    protected void annotateSpan(Span span, ConsumedResult result) {
        span.setAttribute(OtelNullSafety.nonNullLongKey(TelemetryBootstrap.ATTR_HTTP_STATUS_CODE),
                result.status());
        if (result.status() >= 500) {
            span.setStatus(StatusCode.ERROR, "HTTP " + result.status());
        }
    }

    @Override
    protected void recordMetrics(TelemetryBootstrap telemetry, ConsumedResult result,
            double durationSec) {
        Request request = getRequest();
        String method = request.getMethod() != null ? request.getMethod().getName() : "UNKNOWN";
        String host = request.getResourceRef() != null
                ? request.getResourceRef().getHostDomain() : "unknown";
        telemetry.getMetrics().recordHttpClient(method, host != null ? host : "unknown",
                result != null ? result.status() : 0, durationSec);
    }
}
