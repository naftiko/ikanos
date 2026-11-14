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
package io.ikanos.spec.exposes.mcp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Tool Search Specification Element.
 *
 * <p>Says how an MCP endpoint advertises its tools. In {@code listed} mode, {@code tools/list}
 * returns every tool. In {@code deferred} mode, it returns two meta-tools, {@code search-tools}
 * and {@code call-tool}, plus the pinned tools, and the model finds the others by searching. A
 * client may select the mode with {@code ?toolSearch=} on the endpoint URL. Every field is
 * optional; the getters return the defaults.</p>
 */
public class McpToolSearchSpec {

    public static final String MODE_LISTED = "listed";
    public static final String MODE_DEFERRED = "deferred";
    public static final int DEFAULT_MAX_RESULTS = 5;
    public static final int DEFAULT_MAX_TOOL_CALLS = 5;
    public static final String DEFAULT_SEARCH_TOOL_NAME = "search-tools";
    public static final String DEFAULT_CALL_TOOL_NAME = "call-tool";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String mode;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean clientSelectable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Integer maxResults;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Integer maxToolCalls;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> alwaysListed = new CopyOnWriteArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean deferDestructive;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String searchToolName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String callToolName;

    public McpToolSearchSpec() {}

    /** @return the declared mode, or {@code null} */
    public String getMode() {
        return mode;
    }

    /** @return the declared mode, or {@code listed} */
    public String modeOrDefault() {
        return mode != null ? mode : MODE_LISTED;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** @return whether clients may select the mode through the URL; {@code true} by default */
    public boolean clientSelectableOrDefault() {
        return clientSelectable == null || clientSelectable;
    }

    public Boolean getClientSelectable() {
        return clientSelectable;
    }

    public void setClientSelectable(Boolean clientSelectable) {
        this.clientSelectable = clientSelectable;
    }

    /** @return the number of results without a {@code limit}; 5 by default */
    public int maxResultsOrDefault() {
        return maxResults != null ? maxResults : DEFAULT_MAX_RESULTS;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    /** @return the most tools one call-tool request may run; 5 by default */
    public int maxToolCallsOrDefault() {
        return maxToolCalls != null ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;
    }

    public Integer getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(Integer maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /** @return the tools advertised in deferred mode too, as declared */
    public List<String> getAlwaysListed() {
        return alwaysListed;
    }

    public void setAlwaysListed(List<String> alwaysListed) {
        this.alwaysListed.clear();
        if (alwaysListed != null) {
            this.alwaysListed.addAll(alwaysListed);
        }
    }

    /** @return whether destructive tools may be deferred; {@code false} by default */
    public boolean deferDestructiveOrDefault() {
        return deferDestructive != null && deferDestructive;
    }

    public Boolean getDeferDestructive() {
        return deferDestructive;
    }

    public void setDeferDestructive(Boolean deferDestructive) {
        this.deferDestructive = deferDestructive;
    }

    /** @return the search meta-tool name; {@code search-tools} by default */
    public String searchToolNameOrDefault() {
        return searchToolName != null ? searchToolName : DEFAULT_SEARCH_TOOL_NAME;
    }

    public String getSearchToolName() {
        return searchToolName;
    }

    public void setSearchToolName(String searchToolName) {
        this.searchToolName = searchToolName;
    }

    /** @return the call meta-tool name; {@code call-tool} by default */
    public String callToolNameOrDefault() {
        return callToolName != null ? callToolName : DEFAULT_CALL_TOOL_NAME;
    }

    public String getCallToolName() {
        return callToolName;
    }

    public void setCallToolName(String callToolName) {
        this.callToolName = callToolName;
    }
}
