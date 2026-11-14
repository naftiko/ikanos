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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.restlet.Request;
import org.restlet.data.Form;

/**
 * What one MCP request asks of the endpoint beyond its JSON-RPC body.
 *
 * <p>MCP 2026-07-28 is stateless, so a client that wants a different view of the endpoint states
 * it on every request. The endpoint URL is the one place every MCP client lets its operator set
 * once, so these options are read from the URL's query string. On stdio there is no URL, and
 * every request gets {@link #DEFAULT}.</p>
 *
 * @param tagFilter the tags a listed tool must carry at least one of; empty means no filter
 */
public record McpRequestContext(Set<String> tagFilter) {

    /** The query parameter that filters {@code tools/list} by tag, e.g. {@code ?tags=a,b}. */
    public static final String TAGS_PARAMETER = "tags";

    /** The context of a request that asks for nothing: every tool is listed. */
    public static final McpRequestContext DEFAULT = new McpRequestContext(Set.of());

    public McpRequestContext {
        tagFilter = Set.copyOf(tagFilter);
    }

    /**
     * Reads the context from an HTTP request's query string.
     *
     * @param request the inbound request, or {@code null} on stdio
     * @return the context; {@link #DEFAULT} when the request sets nothing
     */
    public static McpRequestContext from(Request request) {
        if (request == null || request.getResourceRef() == null
                || !request.getResourceRef().hasQuery()) {
            return DEFAULT;
        }
        Form query = request.getResourceRef().getQueryAsForm();
        Set<String> tags = parseTags(query.getFirstValue(TAGS_PARAMETER));
        return tags.isEmpty() ? DEFAULT : new McpRequestContext(tags);
    }

    /**
     * Splits a comma-separated tag list. Blank entries are dropped, and the order of first
     * appearance is kept. Tags match exactly, so no case folding is applied.
     */
    static Set<String> parseTags(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim).filter(tag -> !tag.isEmpty())
                .forEach(tags::add);
        return tags;
    }

    /**
     * Whether a tool with these tags is listed under this context.
     *
     * @param toolTags the tool's declared tags
     * @return {@code true} when there is no filter, or the tool carries one of the filter's tags
     */
    public boolean acceptsTags(Iterable<String> toolTags) {
        if (tagFilter.isEmpty()) {
            return true;
        }
        for (String tag : toolTags) {
            if (tagFilter.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
