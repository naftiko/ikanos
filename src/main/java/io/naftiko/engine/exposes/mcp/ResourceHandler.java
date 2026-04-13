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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.OperationStepExecutor;
import io.naftiko.spec.exposes.McpServerResourceSpec;

/**
 * Handles MCP resource reads by serving either dynamic (HTTP-backed) or static (file-backed)
 * resources.
 *
 * <p><b>Dynamic resources</b> reuse {@link OperationStepExecutor} exactly like tools — the same
 * {@code call}/{@code steps}/{@code with} orchestration model applies.</p>
 *
 * <p><b>Static resources</b> read files from a {@code file:///} directory with strict path
 * validation to prevent directory traversal.</p>
 */
public class ResourceHandler {

    private static final Logger logger = Logger.getLogger(ResourceHandler.class.getName());

    /** Allowed path segment characters — no {@code ..}, no special characters. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final Capability capability;
    private final List<McpServerResourceSpec> resourceSpecs;
    private final OperationStepExecutor stepExecutor;
    private final String namespace;

    public ResourceHandler(Capability capability, List<McpServerResourceSpec> resources,
            String namespace) {
        this.capability = capability;
        this.resourceSpecs = new ArrayList<>(resources);
        this.stepExecutor = new OperationStepExecutor(capability);
        this.stepExecutor.setExposeNamespace(namespace);
        this.namespace = namespace;
    }

    /**
     * A single resource content item returned by {@link #read}.
     */
    public static class ResourceContent {
        public final String uri;
        public final String mimeType;
        /** UTF-8 text content, or {@code null} when binary. */
        public final String text;
        /** Base64-encoded binary content, or {@code null} when text. */
        public final String blob;

        private ResourceContent(String uri, String mimeType, String text, String blob) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
            this.blob = blob;
        }

        public static ResourceContent text(String uri, String mimeType, String text) {
            return new ResourceContent(uri, mimeType, text, null);
        }
    }

    /**
     * Read the resource identified by {@code uri}. Resolves URI template parameters from the URI
     * when the spec uses {@code {param}} placeholders.
     *
     * @param uri the concrete resource URI requested by the agent
     * @return list of content items (typically one)
     * @throws IOException when reading a static file fails
     * @throws IllegalArgumentException when no matching resource is found
     */
    public List<ResourceContent> read(String uri) throws Exception {
        for (McpServerResourceSpec spec : resourceSpecs) {
            if (spec.isStatic()) {
                // Static resources: exact URI or prefix match for file listings
                if (uriMatchesStatic(spec, uri)) {
                    return readStatic(spec, uri);
                }
            } else {
                // Dynamic resources: exact or template match
                Map<String, String> templateParams = matchTemplate(spec.getUri(), uri);
                if (templateParams != null) {
                    return readDynamic(spec, uri, templateParams);
                }
            }
        }
        throw new IllegalArgumentException("Unknown resource URI: " + uri);
    }

    /**
     * Enumerate all concrete resource descriptors, expanding static location directories into
     * per-file entries.
     *
     * @return list of {uri, spec} pairs for resources/list
     */
    public List<Map<String, String>> listAll() {
        List<Map<String, String>> result = new ArrayList<>();
        for (McpServerResourceSpec spec : resourceSpecs) {
            if (spec.isStatic()) {
                result.addAll(listStaticFiles(spec));
            } else if (!spec.isTemplate()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("uri", spec.getUri());
                entry.put("name", spec.getName());
                entry.put("label", spec.getLabel() != null ? spec.getLabel() : spec.getName());
                entry.put("description", spec.getDescription());
                if (spec.getMimeType() != null) {
                    entry.put("mimeType", spec.getMimeType());
                }
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Return all resource template descriptors (specs whose URIs contain {@code {param}}).
     */
    public List<McpServerResourceSpec> listTemplates() {
        List<McpServerResourceSpec> templates = new ArrayList<>();
        for (McpServerResourceSpec spec : resourceSpecs) {
            if (spec.isTemplate()) {
                templates.add(spec);
            }
        }
        return templates;
    }

    // ── Static resource helpers ──────────────────────────────────────────────────────────────────

    private boolean uriMatchesStatic(McpServerResourceSpec spec, String requestedUri) {
        String baseUri = spec.getUri();
        return requestedUri.equals(baseUri) || requestedUri.startsWith(baseUri + "/");
    }

    private List<ResourceContent> readStatic(McpServerResourceSpec spec, String requestedUri)
            throws IOException {
        Path baseDir = locationToPath(spec.getLocation());

        String relative = requestedUri.equals(spec.getUri()) ? ""
                : requestedUri.substring(spec.getUri().length() + 1); // strip leading /

        Path target;
        if (relative.isEmpty()) {
            // URI points directly at the base directory — look for an index file or error
            target = baseDir;
        } else {
            // Validate each path segment to prevent directory traversal
            String[] segments = relative.split("/");
            for (String segment : segments) {
                if (!SAFE_SEGMENT.matcher(segment).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid path segment in resource URI: " + segment);
                }
            }
            target = baseDir.resolve(relative).normalize();
        }

        // Containment check after resolving symlinks
        Path resolvedBase = baseDir.toRealPath();
        Path resolvedTarget = target.toRealPath();
        if (!resolvedTarget.startsWith(resolvedBase)) {
            throw new IllegalArgumentException(
                    "Path traversal detected for resource URI: " + requestedUri);
        }

        if (Files.isDirectory(resolvedTarget)) {
            throw new IllegalArgumentException(
                    "Resource URI resolves to a directory, not a file: " + requestedUri);
        }

        String mimeType = spec.getMimeType() != null ? spec.getMimeType()
                : probeMimeType(resolvedTarget);
        String text = Files.readString(resolvedTarget, StandardCharsets.UTF_8);
        return List.of(ResourceContent.text(requestedUri, mimeType, text));
    }

    private List<Map<String, String>> listStaticFiles(McpServerResourceSpec spec) {
        List<Map<String, String>> entries = new ArrayList<>();
        try {
            Path baseDir = locationToPath(spec.getLocation()).toRealPath();
            Files.walk(baseDir)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> {
                        String relative = baseDir.relativize(file).toString().replace('\\', '/');
                        String fileUri = spec.getUri() + "/" + relative;
                        String mimeType = spec.getMimeType() != null ? spec.getMimeType()
                                : probeMimeType(file);
                        Map<String, String> entry = new HashMap<>();
                        entry.put("uri", fileUri);
                        entry.put("name", spec.getName());
                        entry.put("label", spec.getLabel() != null ? spec.getLabel() : spec.getName());
                        entry.put("description", spec.getDescription());
                        if (mimeType != null) {
                            entry.put("mimeType", mimeType);
                        }
                        entries.add(entry);
                    });
        } catch (IOException e) {
            logger.warning("Cannot list static resource directory for '" + spec.getName()
                    + "': " + e.getMessage());
        }
        return entries;
    }

    private static Path locationToPath(String location) {
        // location is a file:/// URI
        return Paths.get(URI.create(location));
    }

    private static String probeMimeType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return probed;
            }
        } catch (IOException ignored) {
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "application/yaml";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    // ── Dynamic resource helpers ─────────────────────────────────────────────────────────────────

    private List<ResourceContent> readDynamic(McpServerResourceSpec spec, String uri,
            Map<String, String> templateParams) throws Exception {

        Map<String, Object> parameters = new HashMap<>(templateParams);
        OperationStepExecutor.mergeWithParameters(spec.getWith(), parameters, namespace);

        OperationStepExecutor.HandlingContext found =
                stepExecutor.execute(spec.getCall(), spec.getSteps(), parameters,
                        "Resource '" + spec.getName() + "'");

        String text = extractContent(spec, found);
        String mimeType = spec.getMimeType() != null ? spec.getMimeType() : "application/json";
        return List.of(ResourceContent.text(uri, mimeType, text));
    }

    private String extractContent(McpServerResourceSpec spec,
            OperationStepExecutor.HandlingContext found) throws IOException {

        if (found == null || found.clientResponse == null
                || found.clientResponse.getEntity() == null) {
            return "";
        }

        String responseText = found.clientResponse.getEntity().getText();
        if (responseText == null) {
            return "";
        }

        String mapped = stepExecutor.applyOutputMappings(responseText, spec.getOutputParameters());
        return mapped != null ? mapped : responseText;
    }

    /**
     * Match a concrete URI against a (possibly templated) spec URI.
     * Returns a map of extracted template parameters, or {@code null} if no match.
     */
    public static Map<String, String> matchTemplate(String specUri, String concreteUri) {
        if (specUri == null || concreteUri == null) {
            return null;
        }
        if (!specUri.contains("{")) {
            // Exact match required for non-template URIs
            return specUri.equals(concreteUri) ? new HashMap<>() : null;
        }

        // Build a regex from the template URI
        StringBuilder regex = new StringBuilder("^");
        Pattern varPattern = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
        Matcher varMatcher = varPattern.matcher(specUri);
        List<String> varNames = new ArrayList<>();
        int last = 0;
        while (varMatcher.find()) {
            regex.append(Pattern.quote(specUri.substring(last, varMatcher.start())));
            regex.append("([^/]+)");
            varNames.add(varMatcher.group(1));
            last = varMatcher.end();
        }
        regex.append(Pattern.quote(specUri.substring(last)));
        regex.append("$");

        Matcher m = Pattern.compile(regex.toString()).matcher(concreteUri);
        if (!m.matches()) {
            return null;
        }
        Map<String, String> params = new ConcurrentHashMap<>();
        for (int i = 0; i < varNames.size(); i++) {
            params.put(varNames.get(i), m.group(i + 1));
        }
        return params;
    }

    public Capability getCapability() {
        return capability;
    }
}
