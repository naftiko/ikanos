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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.routing.Router;

import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.JsonNode;

/**
 * A paginated consumed call drains every page into one list, in each of the four styles, and
 * fails clearly on a bound, a loop, a foreign next link or a page of the wrong shape, while a page
 * that fails returns its own response (HTTP Pagination design doc).
 */
public class HttpPaginationIntegrationTest {

    /** The query string of each request the upstream received. */
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private Component upstream;
    private int port;
    private HttpClientAdapter adapter;

    @BeforeEach
    void startUpstream() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        upstream = new Component();
        upstream.getServers().add(Protocol.HTTP, port);
        upstream.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/cursor", handler((request, response) -> {
                    // Cargo 1 to 5, two per page: c1 -> c2 -> end.
                    String cursor = query(request, "cursor");
                    int from = cursor == null ? 1 : Integer.parseInt(cursor.substring(1));
                    String next = from + 2 <= 5 ? "\"c" + (from + 2) + "\"" : "null";
                    json(response, "{\"cargo\":" + items(from, Math.min(from + 1, 5))
                            + ",\"next_cursor\":" + next + "}");
                }));
                router.attach("/cursor-header", handler((request, response) -> {
                    String cursor = request.getHeaders().getFirstValue("X-Cursor", true);
                    int from = cursor == null ? 1 : Integer.parseInt(cursor);
                    if (from + 2 <= 5) {
                        response.getHeaders().set("X-Next-Cursor", String.valueOf(from + 2));
                    }
                    json(response, items(from, Math.min(from + 1, 5)));
                }));
                router.attach("/linked", handler((request, response) -> {
                    int page = Integer.parseInt(orElse(query(request, "p"), "1"));
                    if (page < 3) {
                        response.getHeaders().set("Link", "</linked?p=" + (page + 1)
                                + ">; rel=\"next\", </linked?p=3>; rel=\"last\"");
                    }
                    json(response, items(page * 10, page * 10));
                }));
                router.attach("/pages", handler((request, response) -> {
                    int page = Integer.parseInt(query(request, "page"));
                    int size = Integer.parseInt(query(request, "per_page"));
                    int from = (page - 1) * size + 1;
                    json(response, "{\"data\":" + items(from, Math.min(from + size - 1, 6))
                            + ",\"meta\":{\"total_pages\":3}}");
                }));
                router.attach("/offset", handler((request, response) -> {
                    int offset = Integer.parseInt(query(request, "offset"));
                    int limit = Integer.parseInt(query(request, "limit"));
                    response.getHeaders().set("X-Total-Count", "5");
                    json(response, items(offset + 1, Math.min(offset + limit, 5)));
                }));
                router.attach("/loop", handler((request, response) ->
                        json(response, "{\"cargo\":[1],\"next_cursor\":\"same\"}")));
                router.attach("/foreign", handler((request, response) -> {
                    response.getHeaders().set("Link",
                            "<https://elsewhere.example/next>; rel=\"next\"");
                    json(response, "[1]");
                }));
                router.attach("/not-array", handler((request, response) ->
                        json(response, "{\"cargo\":{\"id\":1}}")));
                router.attach("/failing", handler((request, response) -> {
                    if (query(request, "cursor") != null) {
                        response.setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
                        response.setEntity("{\"error\":\"down\"}", MediaType.APPLICATION_JSON);
                        return;
                    }
                    json(response, "{\"cargo\":[1],\"next_cursor\":\"c2\"}");
                }));
                return router;
            }
        });
        upstream.start();
        adapter = new HttpClientAdapter(capability(), spec(""));
        adapter.start();
    }

    @AfterEach
    void stop() throws Exception {
        adapter.stop();
        upstream.stop();
    }

    private interface Handler {
        void handle(Request request, Response response);
    }

    private Restlet handler(Handler handler) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                requests.add(request.getResourceRef().getPath() + "?"
                        + orElse(request.getResourceRef().getQuery(), ""));
                handler.handle(request, response);
            }
        };
    }

    private static String query(Request request, String name) {
        Form form = request.getResourceRef().getQueryAsForm();
        return form.getFirstValue(name);
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static void json(Response response, String body) {
        response.setStatus(Status.SUCCESS_OK);
        response.setEntity(body, MediaType.APPLICATION_JSON);
    }

    private static String items(int from, int to) {
        StringBuilder out = new StringBuilder("[");
        for (int i = from; i <= to; i++) {
            out.append(i == from ? "" : ",").append(i);
        }
        return out.append("]").toString();
    }

    private static Capability capability() {
        Capability capability = mock(Capability.class);
        when(capability.getBindings()).thenReturn(Map.of());
        return capability;
    }

    /** The adapter, with {@code extra} appended to the {@code list-cursor} operation's block. */
    private HttpClientSpec spec(String extra) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: registry
                      baseUri: "http://localhost:%d"
                      pagination:
                        maxPages: 10
                      resources:
                        cursor:
                          path: /cursor
                          operations:
                            list-cursor:
                              method: GET
                              pagination:
                                style: cursor
                                items: $.cargo
                                cursor: { from: $.next_cursor, param: cursor }
                                pageSize: { param: limit, value: 2 }
                %s
                        cursor-header:
                          path: /cursor-header
                          operations:
                            list-cursor-header:
                              method: GET
                              pagination:
                                style: cursor
                                items: $
                                cursor: { from: { header: X-Next-Cursor }, header: X-Cursor }
                        linked:
                          path: /linked
                          operations:
                            list-linked:
                              method: GET
                              pagination:
                                style: nextLink
                                items: $
                                nextLink: { from: { header: Link } }
                        pages:
                          path: /pages
                          operations:
                            list-pages:
                              method: GET
                              pagination:
                                style: page
                                items: $.data
                                page: { param: page, start: 1, total: $.meta.total_pages }
                                pageSize: { param: per_page, value: 2 }
                        offset:
                          path: /offset
                          operations:
                            list-offset:
                              method: GET
                              pagination:
                                style: offset
                                items: $
                                offset: { param: offset, total: { header: X-Total-Count } }
                                pageSize: { param: limit, value: 2 }
                        loop:
                          path: /loop
                          pagination:
                            style: cursor
                            items: $.cargo
                            cursor: { from: $.next_cursor, param: cursor }
                          operations:
                            list-loop:
                              method: GET
                        foreign:
                          path: /foreign
                          operations:
                            list-foreign:
                              method: GET
                              pagination:
                                style: nextLink
                                items: $
                                nextLink: { from: { header: Link } }
                        not-array:
                          path: /not-array
                          operations:
                            list-not-array:
                              method: GET
                              pagination:
                                style: cursor
                                items: $.cargo
                                cursor: { from: $.next_cursor, param: cursor }
                        failing:
                          path: /failing
                          operations:
                            list-failing:
                              method: GET
                              pagination:
                                style: cursor
                                items: $.cargo
                                cursor: { from: $.next_cursor, param: cursor }
                """.formatted(VersionHelper.getSchemaVersion(), port, extra);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private ConsumedResult call(String operation) throws Exception {
        return adapter.prepare(operation, new HashMap<>()).invoke();
    }

    private JsonNode drain(String operation) throws Exception {
        ConsumedResult result = call(operation);
        assertEquals(200, result.status());
        return result.document();
    }

    @Test
    void aCursorDrainShouldCollectEveryPageInOrder() throws Exception {
        assertEquals("[1,2,3,4,5]", drain("list-cursor").toString());
        assertEquals(List.of("/cursor?limit=2", "/cursor?limit=2&cursor=c3",
                "/cursor?limit=2&cursor=c5"), requests);
    }

    @Test
    void aCursorMayComeFromAHeaderAndGoBackInOne() throws Exception {
        assertEquals("[1,2,3,4,5]", drain("list-cursor-header").toString());
        assertEquals(3, requests.size());
    }

    @Test
    void aNextLinkDrainShouldFollowRelNextOnTheSameOrigin() throws Exception {
        assertEquals("[10,20,30]", drain("list-linked").toString());
        assertEquals(List.of("/linked?", "/linked?p=2", "/linked?p=3"), requests);
    }

    @Test
    void aPageDrainShouldStopAtTheTotalFromTheBody() throws Exception {
        assertEquals("[1,2,3,4,5,6]", drain("list-pages").toString());
        assertEquals(3, requests.size(), "Page 3 is the last by total_pages: " + requests);
    }

    @Test
    void anOffsetDrainShouldStopAtTheTotalFromAHeader() throws Exception {
        assertEquals("[1,2,3,4,5]", drain("list-offset").toString());
        assertEquals(List.of("/offset?limit=2&offset=0", "/offset?limit=2&offset=2",
                "/offset?limit=2&offset=4"), requests);
    }

    @Test
    void reachingMaxPagesShouldFailRatherThanTruncate() throws Exception {
        adapter = new HttpClientAdapter(capability(), spec("                maxPages: 2"));
        adapter.start();
        Exception failure = assertThrows(Exception.class, () -> call("list-cursor"));
        assertInstanceOf(PaginatedHttpInvocation.PaginationException.class, failure);
        assertTrue(failure.getMessage().contains("maxPages (2)"), failure.getMessage());
    }

    @Test
    void goingPastMaxItemsShouldFail() throws Exception {
        adapter = new HttpClientAdapter(capability(), spec("                maxItems: 3"));
        adapter.start();
        Exception failure = assertThrows(Exception.class, () -> call("list-cursor"));
        assertTrue(failure.getMessage().contains("maxItems (3)"), failure.getMessage());
    }

    @Test
    void aRepeatedPositionShouldFailTheDrain() {
        Exception failure = assertThrows(Exception.class, () -> call("list-loop"));
        assertTrue(failure.getMessage().contains("loops"), failure.getMessage());
    }

    @Test
    void aNextLinkToAnotherOriginShouldNotBeFollowed() {
        Exception failure = assertThrows(Exception.class, () -> call("list-foreign"));
        assertTrue(failure.getMessage().contains("another origin"), failure.getMessage());
    }

    @Test
    void aPageWithoutAnArrayAtItemsShouldFail() {
        Exception failure = assertThrows(Exception.class, () -> call("list-not-array"));
        assertTrue(failure.getMessage().contains("no array"), failure.getMessage());
    }

    @Test
    void aFailingPageShouldBeTheCallsResponse() throws Exception {
        ConsumedResult result = call("list-failing");
        assertEquals(503, result.status());
        assertEquals("{\"error\":\"down\"}", result.text());
    }

    @Test
    void anAdapterDefaultShouldApplyUnderTheOperationsBlock() throws Exception {
        HttpClientSpec spec = spec("");
        assertEquals(10, adapter.paginationOf(spec.getResources().get("cursor").getOperations()
                .get("list-cursor")).maxPagesOrDefault(), "maxPages comes from the adapter");
        assertEquals("cursor", adapter.paginationOf(spec.getResources().get("loop")
                .getOperations().get("list-loop")).getStyle(), "style comes from the resource");
    }

    /** The body of operation {@code list}: its method, then its pagination block's lines. */
    private static String operation(String method, String... paginationLines) {
        StringBuilder out = new StringBuilder(" ".repeat(14) + "method: " + method + "\n"
                + " ".repeat(14) + "pagination:");
        for (String line : paginationLines) {
            out.append("\n").append(" ".repeat(16)).append(line);
        }
        return out.toString();
    }

    @Test
    void loadShouldRefuseAPaginationTheEngineCannotFollow() {
        Map<String, String> refused = Map.of(
                operation("POST", "style: cursor", "items: $",
                        "cursor: { from: $.next, param: c }"), "unsafe method",
                operation("GET", "style: page", "items: $", "page: { param: page }"),
                "pageSize",
                operation("GET", "style: cursor", "cursor: { from: $.next, param: c }"),
                "items");
        refused.forEach((operation, reason) -> {
            String yaml = """
                    ikanos: "%s"
                    capability:
                      consumes:
                        - type: http
                          namespace: registry
                          baseUri: "http://localhost:1"
                          resources:
                            things:
                              path: /things
                              operations:
                                list:
                    %s
                    """.formatted(VersionHelper.getSchemaVersion(), operation);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new HttpClientAdapter(capability(), (HttpClientSpec) IkanosMappers
                            .lenientYaml().readValue(yaml, IkanosSpec.class).getCapability()
                            .getConsumes().get(0)));
            assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        });
    }
}
