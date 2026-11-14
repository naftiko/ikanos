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
package io.ikanos.engine.exposes.mcp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.engine.exposes.mcp.search.ToolSearchMode;

class McpRequestContextTest {

    @Test
    void fromShouldReturnDefaultWhenThereIsNoRequest() {
        assertSame(McpRequestContext.DEFAULT, McpRequestContext.from(null, null));
    }

    @Test
    void fromShouldReturnDefaultWhenTheUrlHasNoQuery() {
        assertSame(McpRequestContext.DEFAULT,
                McpRequestContext.from(
new Request(Method.POST, "http://localhost/mcp"), null));
    }

    @Test
    void fromShouldReadTheTagsParameter() {
        McpRequestContext context = McpRequestContext.from(
new Request(Method.POST, "http://localhost/mcp?tags=ops,kubernetes"), null);

        assertEquals(Set.of("ops", "kubernetes"), context.tagFilter());
    }

    @Test
    void fromShouldUseTheFirstValueWhenTagsIsRepeated() {
        McpRequestContext context = McpRequestContext.from(
new Request(Method.POST, "http://localhost/mcp?tags=ops&tags=billing"), null);

        assertEquals(Set.of("ops"), context.tagFilter());
    }

    @Test
    void fromShouldDecodeAPercentEncodedComma() {
        McpRequestContext context = McpRequestContext.from(
new Request(Method.POST, "http://localhost/mcp?tags=ops%2Cbilling"), null);

        assertEquals(Set.of("ops", "billing"), context.tagFilter());
    }

    @Test
    void parseTagsShouldDropBlankEntriesAndTrimSpaces() {
        assertEquals(Set.of("ops", "billing"), McpRequestContext.parseTags(" ops, ,billing ,"));
    }

    @Test
    void parseTagsShouldReturnNoTagsForABlankValue() {
        assertTrue(McpRequestContext.parseTags("  ").isEmpty());
    }

    @Test
    void acceptsTagsShouldAcceptEveryToolWithoutAFilter() {
        assertTrue(McpRequestContext.DEFAULT.acceptsTags(List.of()));
    }

    @Test
    void acceptsTagsShouldAcceptAToolCarryingOneOfTheFilterTags() {
        assertTrue(new McpRequestContext(Set.of("ops", "billing"), ToolSearchMode.LISTED)
                .acceptsTags(List.of("support", "billing")));
    }

    @Test
    void acceptsTagsShouldRejectAToolWithoutTagsWhenFiltering() {
        assertFalse(new McpRequestContext(Set.of("ops"), ToolSearchMode.LISTED).acceptsTags(List.of()));
    }
}
