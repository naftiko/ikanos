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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the query helpers of forward mode (#953), with no server: the end-to-end cases
 * are in {@link ForwardQueryIntegrationTest}.
 */
public class ForwardQueryTest {

    @Test
    void forwardTargetRefJoinsTheQueryToTheBaseUri() {
        assertEquals("http://h/v1/items",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", null));
        assertEquals("http://h/v1/items",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", ""));
        assertEquals("http://h/v1/items?a=1&b=%2F",
                ResourceRestlet.forwardTargetRef("http://h/v1", "/items", "a=1&b=%2F"));
        assertEquals("http://h/v1?key=k&a=1",
                ResourceRestlet.forwardTargetRef("http://h/v1?key=k", "", "a=1"));
        assertEquals("http://h/v1/items?key=k&a=1",
                ResourceRestlet.forwardTargetRef("http://h/v1?key=k", "/items", "a=1"),
                "the captured path goes before the base URI's query");
        assertEquals("http://h/v1/items?key=k",
                ResourceRestlet.forwardTargetRef("http://h/v1?key=k", "/items", null),
                "the base URI's query is kept when the request has none");
    }

    @Test
    void filterForwardedQueryShouldDropPairsWhoseDecodedNameIsExcluded() {
        Set<String> excluded = Set.of("api_key", "api-version");

        assertEquals("a=1&b=%2F",
                ResourceRestlet.filterForwardedQuery("a=1&api_key=s&b=%2F", excluded));
        assertEquals("a=1", ResourceRestlet.filterForwardedQuery("api%5Fkey=s&a=1", excluded),
                "the name is compared once percent-decoded");
        assertEquals("a=1", ResourceRestlet.filterForwardedQuery("api-version&a=1", excluded),
                "a name without a value is dropped too");
        assertEquals("API_KEY=s&a=1",
                ResourceRestlet.filterForwardedQuery("API_KEY=s&a=1", excluded),
                "names are case-sensitive, like the server-side check");
        assertEquals("", ResourceRestlet.filterForwardedQuery("api_key=s", excluded));
    }

    @Test
    void filterForwardedQueryShouldKeepTheQueryUntouchedWhenNothingIsExcluded() {
        assertEquals("a=%zz&b=x+y&c",
                ResourceRestlet.filterForwardedQuery("a=%zz&b=x+y&c", Set.of("api_key")),
                "a malformed escape is forwarded as received");
        assertEquals("a=1", ResourceRestlet.filterForwardedQuery("a=1", Set.of()));
        assertNull(ResourceRestlet.filterForwardedQuery(null, Set.of("api_key")));
    }

    @Test
    void baseUriQueryNamesShouldReturnTheDecodedNamesOfTheBaseUriQuery() {
        assertEquals(Set.of("key", "api_version", "flag"),
                ResourceRestlet.baseUriQueryNames("http://h/v1?key=k&api%5Fversion=2&flag"));
        assertEquals(Set.of(), ResourceRestlet.baseUriQueryNames("http://h/v1"));
        assertEquals(Set.of(), ResourceRestlet.baseUriQueryNames("http://h/v1?"));
        assertEquals(Set.of(), ResourceRestlet.baseUriQueryNames(null));
    }
}
