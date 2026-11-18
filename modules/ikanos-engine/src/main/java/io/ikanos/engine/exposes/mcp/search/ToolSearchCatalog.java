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
package io.ikanos.engine.exposes.mcp.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.restlet.Context;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpToolSearchSpec;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tool search for one MCP endpoint: its configuration, the tools it pins and the tools it lets
 * clients search, the two meta-tools of the deferred mode, and the search index.
 *
 * <p>Built once per adapter, after its tools. The catalog itself uses no search library: the
 * index comes from the {@link ToolSearchIndexFactory} found on the classpath and is created on
 * the first search, so an endpoint nobody searches pays nothing.</p>
 *
 * <p>The constructor validates the configuration. A capability that declares a {@code toolSearch}
 * block fails to load on an error; one that does not is never failed by the feature, which is
 * on by default: a tool name that collides with a meta-tool only disables client selection.</p>
 */
public final class ToolSearchCatalog {

    /** Longest query text accepted, as in Anthropic's BM25 tool search. */
    public static final int MAX_QUERY_LENGTH = 500;

    /** Largest {@code limit} a model may ask for, and largest {@code maxResults}. */
    public static final int MAX_LIMIT = 50;

    /** Largest {@code maxToolCalls}. */
    public static final int MAX_TOOL_CALLS = 20;

    private final String namespace;
    private final boolean declared;
    private final ToolSearchMode defaultMode;
    private final boolean clientSelectable;
    private final int maxResults;
    private final int maxToolCalls;
    private final String searchToolName;
    private final String callToolName;
    private final Optional<ToolSearchIndexFactory> provider;
    private final Map<String, McpSchema.Tool> toolsByName;
    private final Map<String, String> labels;
    private final Map<String, List<String>> tags;
    private final List<McpSchema.Tool> pinned;
    private final List<McpSchema.Tool> searchable;
    private final Set<String> searchableNames;
    private final List<McpSchema.Tool> metaTools;
    private final AtomicBoolean loggedIgnoredParameter = new AtomicBoolean();
    private final AtomicBoolean loggedMissingProvider = new AtomicBoolean();
    private final Object indexLock = new Object();
    private volatile ToolSearchIndex index;

    /**
     * Builds and validates the catalog.
     *
     * @param serverSpec the MCP adapter spec
     * @param tools the advertised tools, in authored order
     * @param labels display titles by tool name
     * @param tags declared tags by tool name
     * @param provider the installed tool search provider, if any
     * @throws IllegalArgumentException when a declared {@code toolSearch} block is invalid
     */
    public ToolSearchCatalog(McpServerSpec serverSpec, List<McpSchema.Tool> tools,
            Map<String, String> labels, Map<String, List<String>> tags,
            Optional<ToolSearchIndexFactory> provider) {
        McpToolSearchSpec spec = serverSpec.getToolSearch();
        this.namespace = serverSpec.getNamespace();
        this.declared = spec != null;
        McpToolSearchSpec effective = spec != null ? spec : new McpToolSearchSpec();
        this.defaultMode = ToolSearchMode.parse(effective.modeOrDefault());
        this.maxResults = effective.maxResultsOrDefault();
        this.maxToolCalls = effective.maxToolCallsOrDefault();
        this.searchToolName = effective.searchToolNameOrDefault();
        this.callToolName = effective.callToolNameOrDefault();
        this.provider = provider;
        this.labels = labels;
        this.tags = tags;

        Map<String, McpSchema.Tool> byName = new LinkedHashMap<>();
        tools.forEach(tool -> byName.put(tool.name(), tool));
        this.toolsByName = Collections.unmodifiableMap(byName);

        this.clientSelectable = validate(effective) && effective.clientSelectableOrDefault();

        Set<String> pinnedNames = new LinkedHashSet<>(effective.getAlwaysListed());
        if (!effective.deferDestructiveOrDefault()) {
            tools.stream().filter(ToolSearchCatalog::isDestructive)
                    .forEach(tool -> pinnedNames.add(tool.name()));
        }
        // An app-only tool is hidden from the model by the host: never let search expose it.
        tools.stream().filter(ToolSearchCatalog::isAppOnly)
                .forEach(tool -> pinnedNames.add(tool.name()));
        List<McpSchema.Tool> pinnedTools = new ArrayList<>();
        List<McpSchema.Tool> searchableTools = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            (pinnedNames.contains(tool.name()) ? pinnedTools : searchableTools).add(tool);
        }
        this.pinned = List.copyOf(pinnedTools);
        this.searchable = List.copyOf(searchableTools);
        Set<String> searchableNameSet = new LinkedHashSet<>();
        searchableTools.forEach(tool -> searchableNameSet.add(tool.name()));
        this.searchableNames = Collections.unmodifiableSet(searchableNameSet);
        this.metaTools = List.of(buildSearchTool(serverSpec.getDescription()), buildCallTool());
    }

    /**
     * Checks the configuration. Returns {@code false} when client selection must be disabled
     * because a tool of an endpoint without a {@code toolSearch} block uses a meta-tool name.
     */
    private boolean validate(McpToolSearchSpec effective) {
        String collision = toolsByName.containsKey(searchToolName) ? searchToolName
                : toolsByName.containsKey(callToolName) ? callToolName : null;
        if (!declared) {
            if (collision != null) {
                Context.getCurrentLogger().log(Level.WARNING, "Tool search disabled on ''{0}'':"
                        + " tool ''{1}'' uses a reserved meta-tool name; set"
                        + " toolSearch.searchToolName/callToolName to enable it.",
                        new Object[] {namespace, collision});
                return false;
            }
            return true;
        }
        // A Capability built in code skips JSON Schema validation: check its bounds here too.
        checkRange("maxResults", maxResults, MAX_LIMIT);
        checkRange("maxToolCalls", maxToolCalls, MAX_TOOL_CALLS);
        for (String name : effective.getAlwaysListed()) {
            if (!toolsByName.containsKey(name)) {
                throw new IllegalArgumentException("toolSearch.alwaysListed on '" + namespace
                        + "' names '" + name + "', which is not a tool of this endpoint.");
            }
        }
        if (searchToolName.equals(callToolName)) {
            throw new IllegalArgumentException("toolSearch on '" + namespace + "' gives the same"
                    + " name, '" + searchToolName + "', to searchToolName and callToolName.");
        }
        if (collision != null) {
            throw new IllegalArgumentException("Tool '" + collision + "' on '" + namespace
                    + "' uses a meta-tool name of toolSearch. Rename the tool, or set"
                    + " toolSearch.searchToolName/callToolName.");
        }
        if (defaultMode == ToolSearchMode.DEFERRED && provider.isEmpty()) {
            throw new IllegalArgumentException("toolSearch.mode is deferred on '" + namespace
                    + "' but no tool search provider is installed. Add the ikanos-tool-search"
                    + " module to the classpath.");
        }
        if (provider.isEmpty()) {
            Context.getCurrentLogger().log(Level.INFO, "Tool search is not installed: ''{0}''"
                    + " lists every tool. Add the ikanos-tool-search module to offer deferred"
                    + " mode.", namespace);
        }
        return true;
    }

    private void checkRange(String setting, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException("toolSearch." + setting + " on '" + namespace
                    + "' is " + value + "; use a value from 1 to " + max + ".");
        }
    }

    /**
     * Resolves the mode of one request from the value of its {@code toolSearch} URL parameter.
     *
     * @param requested the parameter value, or {@code null} when absent
     * @return the mode the request gets
     * @throws IllegalArgumentException for a value other than {@code listed} or {@code deferred},
     *         when clients may select the mode
     */
    public ToolSearchMode resolveMode(String requested) {
        if (requested == null || requested.isEmpty()) {
            return defaultMode;
        }
        if (!clientSelectable) {
            if (loggedIgnoredParameter.compareAndSet(false, true)) {
                Context.getCurrentLogger().log(Level.FINE, "Ignoring toolSearch={0} on ''{1}'':"
                        + " clients cannot select the tool search mode.",
                        new Object[] {requested, namespace});
            }
            return defaultMode;
        }
        ToolSearchMode mode = ToolSearchMode.parse(requested);
        if (mode == ToolSearchMode.DEFERRED && provider.isEmpty()) {
            if (loggedMissingProvider.compareAndSet(false, true)) {
                Context.getCurrentLogger().log(Level.INFO, "A client asked for toolSearch=deferred"
                        + " on ''{0}'', but no tool search provider is installed: listing every"
                        + " tool instead. Add the ikanos-tool-search module.", namespace);
            }
            return ToolSearchMode.LISTED;
        }
        return mode;
    }

    /**
     * Searches the searchable tools within a scope of tags, such as a tag filter on the endpoint
     * URL. The scope is a separate condition from the query's own tags: a tool must carry one tag
     * of each. The index never sees the scope, so every provider applies it the same way.
     *
     * @param query the search
     * @param scopeTags only return tools carrying at least one of these tags; empty means any
     * @return the matching tool names, best first
     * @throws ToolSearchUnavailableException when no provider is installed or the provider fails
     */
    public List<String> search(ToolSearchQuery query, Set<String> scopeTags) {
        if (scopeTags == null || scopeTags.isEmpty()) {
            return search(query);
        }
        // Rank every match, then keep the scoped ones, so the limit applies after the scope.
        List<String> ranked = search(new ToolSearchQuery(query.text(), query.anyTags(),
                query.readOnlyOnly(), Math.max(1, searchable.size())));
        List<String> scoped = new ArrayList<>();
        for (String name : ranked) {
            if (scoped.size() == query.limit()) {
                break;
            }
            if (tags.getOrDefault(name, List.of()).stream().anyMatch(scopeTags::contains)) {
                scoped.add(name);
            }
        }
        return scoped;
    }

    /**
     * Searches the searchable tools, creating the index on the first call.
     *
     * @param query the search
     * @return the matching tool names, best first
     * @throws ToolSearchUnavailableException when no provider is installed or the provider fails
     */
    public List<String> search(ToolSearchQuery query) {
        ToolSearchIndex current = index;
        if (current == null) {
            synchronized (indexLock) {
                current = index;
                if (current == null) {
                    current = createIndex();
                    index = current;
                }
            }
        }
        try {
            return current.search(query);
        } catch (RuntimeException | LinkageError e) {
            throw new ToolSearchUnavailableException("Tool search failed on " + namespace, e);
        }
    }

    private ToolSearchIndex createIndex() {
        ToolSearchIndexFactory factory = provider.orElseThrow(() ->
                new ToolSearchUnavailableException("No tool search provider is installed", null));
        try {
            return factory.create(documents());
        } catch (RuntimeException | LinkageError e) {
            throw new ToolSearchUnavailableException(
                    "The " + factory.type() + " tool search provider failed to build its index", e);
        }
    }

    /** @return one document per searchable tool, in authored order */
    public List<ToolSearchDocument> documents() {
        List<ToolSearchDocument> documents = new ArrayList<>();
        for (McpSchema.Tool tool : searchable) {
            documents.add(new ToolSearchDocument(tool.name(), labels.get(tool.name()),
                    tool.description(), tags.getOrDefault(tool.name(), List.of()),
                    parametersText(tool.inputSchema()), isReadOnly(tool)));
        }
        return documents;
    }

    /** The names and descriptions of a tool's inputs, nested ones included, as one text. */
    static String parametersText(Object schema) {
        StringBuilder text = new StringBuilder();
        appendParameters(schema, text);
        return text.toString().strip();
    }

    @SuppressWarnings("unchecked")
    private static void appendParameters(Object schema, StringBuilder text) {
        if (!(schema instanceof Map<?, ?> map)) {
            return;
        }
        if (map.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                text.append(property.getKey()).append(' ');
                if (property.getValue() instanceof Map<?, ?> child) {
                    if (child.get("description") instanceof String description) {
                        text.append(description).append(' ');
                    }
                    appendParameters(child, text);
                }
            }
        }
        if (map.get("items") instanceof Map<?, ?> items) {
            appendParameters(items, text);
        }
    }

    private McpSchema.Tool buildSearchTool(String serverDescription) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", orderedMap("type", "string",
                "description", "What you want to do, in natural language or keywords."));
        properties.put("tags", orderedMap("type", "array", "items", Map.of("type", "string"),
                "description", "Only return tools with at least one of these tags."));
        properties.put("readOnly", orderedMap("type", "boolean",
                "description", "Only return tools that do not modify state."));
        properties.put("limit", orderedMap("type", "integer", "minimum", 1, "maximum", MAX_LIMIT,
                "description", "Maximum number of tools to return. Defaults to " + maxResults
                        + "."));
        Map<String, Object> inputSchema = orderedMap("type", "object", "properties", properties,
                "required", List.of("query"), "additionalProperties", false);

        Map<String, Object> outputSchema = orderedMap("type", "object",
                "properties", orderedMap(
                        "tools", orderedMap("type", "array",
                                "items", Map.of("type", "object"),
                                "description", "Full definitions of the matching tools, best"
                                        + " first, in the shape of tools/list entries."),
                        "hint", orderedMap("type", "string",
                                "description", "Present when no tool matched: how to search"
                                        + " again.")),
                "required", List.of("tools"));

        return McpSchema.Tool.builder(searchToolName, inputSchema)
                .description(searchDescription(serverDescription))
                .outputSchema(outputSchema)
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                .build();
    }

    /**
     * The description of the search meta-tool. It names the categories that exist, so the model
     * knows what it can find without listing the tools.
     */
    String searchDescription(String serverDescription) {
        StringBuilder text = new StringBuilder("Search the ").append(namespace)
                .append(" tools and return their full definitions.");
        if (serverDescription != null && !serverDescription.isBlank()) {
            String sentence = serverDescription.strip();
            text.append(' ').append(sentence);
            if (!sentence.endsWith(".")) {
                text.append('.');
            }
        }
        Set<String> categories = new TreeSet<>();
        for (McpSchema.Tool tool : searchable) {
            categories.addAll(tags.getOrDefault(tool.name(), List.of()));
        }
        if (!categories.isEmpty()) {
            text.append(" Categories: ").append(String.join(", ", categories)).append('.');
        }
        text.append(" Call a returned tool with ").append(callToolName).append('.');
        return text.toString();
    }

    private McpSchema.Tool buildCallTool() {
        Map<String, Object> callProperties = new LinkedHashMap<>();
        callProperties.put("name", orderedMap("type", "string",
                "description", "Name of a tool returned by " + searchToolName + "."));
        callProperties.put("arguments", orderedMap("type", "object",
                "description", "Arguments of that tool, as its inputSchema describes."));
        Map<String, Object> call = orderedMap("type", "object", "properties", callProperties,
                "required", List.of("name"), "additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("calls", orderedMap("type", "array", "minItems", 1,
                "maxItems", maxToolCalls, "items", call,
                "description", "The tools to call, in order."));
        Map<String, Object> inputSchema = orderedMap("type", "object", "properties", properties,
                "required", List.of("calls"), "additionalProperties", false);

        Map<String, Object> outputSchema = orderedMap("type", "object",
                "properties", Map.of("results", orderedMap("type", "array",
                        "items", Map.of("type", "object"),
                        "description", "One entry per call, in call order: the tool name and"
                                + " its result, as a direct tools/call returns it, except that"
                                + " a text block repeating its structuredContent is left out,"
                                + " and an image, audio or resource block is shown without its"
                                + " data, with the contentIndex of the block in this result's"
                                + " content that carries it.")),
                "required", List.of("results"));

        boolean deferredDestructive = searchable.stream().anyMatch(ToolSearchCatalog::isDestructive);
        return McpSchema.Tool.builder(callToolName, inputSchema)
                .description("Call up to " + maxToolCalls + " tools found with " + searchToolName
                        + ", in order, each with the arguments its inputSchema describes. A"
                        + " failing call does not stop the others.")
                .outputSchema(outputSchema)
                .annotations(new McpSchema.ToolAnnotations(null, false, deferredDestructive, false,
                        true, null))
                .build();
    }

    private static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Whether a client must treat the tool as destructive, with the MCP defaults for hints left
     * out: {@code readOnlyHint} false and {@code destructiveHint} true. Only a read-only tool or
     * one that declares {@code destructiveHint: false} is not.
     */
    static boolean isDestructive(McpSchema.Tool tool) {
        McpSchema.ToolAnnotations annotations = tool.annotations();
        if (annotations == null) {
            return true;
        }
        return !Boolean.TRUE.equals(annotations.readOnlyHint())
                && !Boolean.FALSE.equals(annotations.destructiveHint());
    }

    /**
     * Whether the tool's MCP Apps {@code _meta.ui.visibility} leaves out {@code model}, so the
     * host hides it from the model and only its view may call it.
     */
    static boolean isAppOnly(McpSchema.Tool tool) {
        if (tool.meta() == null || !(tool.meta().get("ui") instanceof Map<?, ?> ui)) {
            return false;
        }
        return ui.get("visibility") instanceof List<?> visibility && !visibility.contains("model");
    }

    static boolean isReadOnly(McpSchema.Tool tool) {
        return tool.annotations() != null
                && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    /** @return the mode of a request that does not select one */
    public ToolSearchMode defaultMode() {
        return defaultMode;
    }

    /** @return whether clients may select the mode through the URL */
    public boolean isClientSelectable() {
        return clientSelectable;
    }

    /** @return whether a tool search provider is installed */
    public boolean isAvailable() {
        return provider.isPresent();
    }

    /** @return the search and call meta-tools, in that order */
    public List<McpSchema.Tool> metaTools() {
        return metaTools;
    }

    /** @return the tools advertised in deferred mode too, in authored order */
    public List<McpSchema.Tool> pinnedTools() {
        return pinned;
    }

    /** @return the tools found only by searching in deferred mode, in authored order */
    public List<McpSchema.Tool> searchableTools() {
        return searchable;
    }

    /** @return whether the name is the search meta-tool */
    public boolean isSearchTool(String name) {
        return searchToolName.equals(name);
    }

    /** @return whether the name is the call meta-tool */
    public boolean isCallTool(String name) {
        return callToolName.equals(name);
    }

    /** @return whether the tool is found only by searching in deferred mode */
    public boolean isSearchable(String name) {
        return searchableNames.contains(name);
    }

    /** @return the advertised tool with this name, if any */
    public Optional<McpSchema.Tool> tool(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /** @return the number of results when the model sets no {@code limit} */
    public int maxResults() {
        return maxResults;
    }

    /** @return the most tools one call-tool request may run */
    public int maxToolCalls() {
        return maxToolCalls;
    }

    /** @return the search meta-tool name */
    public String searchToolName() {
        return searchToolName;
    }

    /** @return the call meta-tool name */
    public String callToolName() {
        return callToolName;
    }

    /** @return the endpoint namespace */
    public String namespace() {
        return namespace;
    }
}
