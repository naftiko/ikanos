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
package io.naftiko.engine.exposes.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.naftiko.spec.exposes.McpPromptMessageSpec;
import io.naftiko.spec.exposes.McpServerPromptSpec;

/**
 * Handles MCP {@code prompts/get} requests by rendering prompt templates.
 *
 * <p><b>Inline prompts</b> substitute {@code {{arg}}} placeholders in the declared message list.
 * Argument values are treated as literal text — nested {@code {{...}}} in values are NOT
 * re-interpolated (prevents prompt injection).</p>
 *
 * <p><b>File-based prompts</b> load the file at the {@code location} URI, substitute arguments,
 * and return the content as a single {@code user} role message.</p>
 */
public class PromptHandler {

    /** Matches {{argName}} placeholders — arg names are alphanumeric + underscore. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    private final Map<String, McpServerPromptSpec> promptSpecs;

    public PromptHandler(List<McpServerPromptSpec> prompts) {
        this.promptSpecs = new ConcurrentHashMap<>();
        for (McpServerPromptSpec prompt : prompts) {
            promptSpecs.put(prompt.getName(), prompt);
        }
    }

    /**
     * A rendered prompt message returned by {@link #render(String, Map)}.
     */
    public static class RenderedMessage {
        public final String role;
        public final String text;

        public RenderedMessage(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /**
     * Render a prompt by name with the provided arguments.
     *
     * @param name      the prompt name
     * @param arguments key-value argument map (values are always treated as strings)
     * @return ordered list of rendered messages
     * @throws IllegalArgumentException when the prompt is unknown
     * @throws IOException              when a file-based prompt cannot be read
     */
    public List<RenderedMessage> render(String name, Map<String, Object> arguments)
            throws IOException {

        McpServerPromptSpec spec = promptSpecs.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown prompt: " + name);
        }

        Map<String, String> args = toStringMap(arguments);

        if (spec.isFileBased()) {
            return renderFileBased(spec, args);
        } else {
            return renderInline(spec, args);
        }
    }

    /**
     * Return all prompt specs (for {@code prompts/list}).
     */
    public List<McpServerPromptSpec> listAll() {
        return new ArrayList<>(promptSpecs.values());
    }

    // ── Inline rendering ─────────────────────────────────────────────────────────────────────────

    private List<RenderedMessage> renderInline(McpServerPromptSpec spec,
            Map<String, String> args) {
        List<RenderedMessage> messages = new ArrayList<>();
        for (McpPromptMessageSpec msg : spec.getTemplate()) {
            String rendered = substitute(msg.getContent(), args);
            messages.add(new RenderedMessage(msg.getRole(), rendered));
        }
        return messages;
    }

    // ── File-based rendering ─────────────────────────────────────────────────────────────────────

    private List<RenderedMessage> renderFileBased(McpServerPromptSpec spec,
            Map<String, String> args) throws IOException {
        Path file = Paths.get(URI.create(spec.getLocation()));
        if (!Files.isRegularFile(file)) {
            throw new IOException(
                    "Prompt file not found for '" + spec.getName() + "': " + spec.getLocation());
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String rendered = substitute(content, args);
        return List.of(new RenderedMessage("user", rendered));
    }

    // ── Argument substitution ────────────────────────────────────────────────────────────────────

    /**
     * Replace all {@code {{argName}}} occurrences in {@code template} with the corresponding value
     * from {@code args}. Unrecognised placeholders are left as-is. Argument values are escaped so
     * that any {@code {{...}}} sequences they contain are NOT treated as additional placeholders
     * (prevents prompt injection).
     */
    public static String substitute(String template, Map<String, String> args) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String argName = m.group(1);
            String value = args.getOrDefault(argName, m.group(0)); // leave placeholder if missing
            // Escape literal backslashes and dollar signs for Matcher.appendReplacement
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> toStringMap(Map<String, Object> arguments) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (arguments != null) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }
}
