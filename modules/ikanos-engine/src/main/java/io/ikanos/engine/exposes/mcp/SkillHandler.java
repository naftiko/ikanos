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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.restlet.Context;

import io.ikanos.engine.exposes.mcp.SkillManifest.SkillFile;
import io.ikanos.spec.exposes.mcp.McpServerSkillSpec;

/**
 * Serves the Agent Skills of an MCP server through the Skills Extension (SEP-2640,
 * {@code io.modelcontextprotocol/skills}).
 *
 * <p>Every skill is validated and snapshotted once, at adapter start, into a {@link SkillManifest}:
 * the rendered {@code SKILL.md} plus the published files of its {@code location}, each with its
 * {@code sha256:} digest and size. Requests are then answered from the snapshots only.</p>
 *
 * <p>{@code location} is a curated publication directory, not a repository root: dotfiles,
 * dot-directories and editor backups are not published, and a symbolic link fails the load.</p>
 */
public class SkillHandler {

    /** Extension identifier advertised in {@code server/discover}. */
    public static final String EXTENSION_ID = "io.modelcontextprotocol/skills";

    /** URI scheme of the skill files. */
    public static final String SCHEME = "skill://";

    /** The skill's entry file, always first in its manifest. */
    public static final String SKILL_FILE = "SKILL.md";

    /** SEP-2640 per-skill limit on the number of files, {@code SKILL.md} included. */
    static final int MAX_FILES = 512;

    /** SEP-2640 per-skill limit on the summed size of the files, {@code SKILL.md} included. */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_NAME_LENGTH = 64;

    /** A publishable path segment: no leading dot, no separators, no special characters. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*$");

    private static final String MARKDOWN = "text/markdown";

    private final Map<String, SkillManifest> manifests;

    /**
     * Validate and snapshot the declared skills.
     *
     * @param skills       the declared skills, keyed by name
     * @param toolNames    the names of the tools declared on the same MCP server
     * @param resourceUris the URIs of the resources declared on the same MCP server
     * @throws IllegalStateException when a skill is invalid or unsafe to publish
     */
    public SkillHandler(Map<String, McpServerSkillSpec> skills, Set<String> toolNames,
            Collection<String> resourceUris) {
        Map<String, SkillManifest> built = new LinkedHashMap<>();
        if (skills != null && !skills.isEmpty()) {
            for (String uri : resourceUris) {
                if (uri != null && uri.startsWith(SCHEME)) {
                    throw new IllegalStateException("MCP resource URI '" + uri
                            + "' uses the " + SCHEME + " scheme, which is reserved for skills.");
                }
            }
            for (McpServerSkillSpec skill : skills.values()) {
                SkillManifest manifest = snapshot(skill, toolNames);
                if (manifest != null) {
                    built.put(manifest.name(), manifest);
                }
            }
        }
        this.manifests = Collections.unmodifiableMap(built);
    }

    /** @return {@code true} when this server serves no skill. */
    public boolean isEmpty() {
        return manifests.isEmpty();
    }

    /** @return every served skill, in declaration order. */
    public List<SkillManifest> list() {
        return List.copyOf(manifests.values());
    }

    /**
     * Find a skill by the URI of its {@code SKILL.md}.
     *
     * @throws IllegalArgumentException when no served skill has this URI
     */
    public SkillManifest get(String skillUri) {
        for (SkillManifest manifest : manifests.values()) {
            if (manifest.uri().equals(skillUri)) {
                return manifest;
            }
        }
        throw new IllegalArgumentException("Unknown skill: " + skillUri);
    }

    /** @return {@code true} when the URI is in the {@code skill://} scheme. */
    public static boolean isSkillUri(String uri) {
        return uri != null && uri.startsWith(SCHEME);
    }

    /**
     * Read one published skill file.
     *
     * @throws IllegalArgumentException when no served skill publishes this URI
     */
    public ResourceHandler.ResourceContent read(String uri) {
        for (SkillManifest manifest : manifests.values()) {
            SkillFile file = manifest.file(uri);
            if (file == null) {
                continue;
            }
            ResourceHandler.ResourceContent content =
                    ResourceHandler.isBinaryMime(file.mimeType())
                            ? ResourceHandler.ResourceContent.binary(uri, file.mimeType(),
                                    Base64.getEncoder().encodeToString(file.bytes()))
                            : ResourceHandler.ResourceContent.text(uri, file.mimeType(),
                                    new String(file.bytes(), StandardCharsets.UTF_8));
            return SKILL_FILE.equals(file.path()) && manifest.meta() != null
                    ? content.withMeta(manifest.meta())
                    : content;
        }
        throw new IllegalArgumentException("Unknown skill resource: " + uri);
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Validate one skill and build its manifest, or return {@code null} (with a warning) when it
     * exceeds the SEP-2640 size limits.
     */
    SkillManifest snapshot(McpServerSkillSpec skill, Set<String> toolNames) {
        String name = skill.getName();
        if (name == null || name.length() > MAX_NAME_LENGTH || !SKILL_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Skill name '" + name + "' is not a valid Agent Skills"
                    + " name: lowercase letters, digits and single hyphens, at most "
                    + MAX_NAME_LENGTH + " characters.");
        }
        if (skill.getDescription() == null || skill.getDescription().isBlank()) {
            throw new IllegalStateException("Skill '" + name + "' must have a description.");
        }
        for (String tool : skill.getTools()) {
            if (!toolNames.contains(tool)) {
                throw new IllegalStateException("Skill '" + name + "' depends on tool '" + tool
                        + "', which is not declared on the same MCP server. Declared tools: "
                        + toolNames);
            }
        }
        if (!skill.getInstructions().isEmpty() && skill.getLocation() == null) {
            throw new IllegalStateException("Skill '" + name
                    + "' declares instructions but has no 'location'.");
        }

        Path root = skill.getLocation() != null ? resolveLocation(name, skill.getLocation()) : null;
        Map<String, Path> published = root != null ? publishedFiles(name, root)
                : Collections.emptyMap();

        for (Map.Entry<String, String> instruction : skill.getInstructions().entrySet()) {
            if (!published.containsKey(instruction.getValue())) {
                throw new IllegalStateException("Instruction '" + instruction.getKey()
                        + "' of skill '" + name + "' points to '" + instruction.getValue()
                        + "', which is missing from the skill location or excluded from"
                        + " publication.");
            }
        }

        Map<String, Object> frontmatter = SkillMarkdownRenderer.frontmatter(skill);
        String body = SkillMarkdownRenderer.generatedBody(skill);
        Path authored = root != null ? root.resolve(SKILL_FILE) : null;
        if (authored != null && Files.isRegularFile(authored)) {
            SkillMarkdownRenderer.Authored parsed =
                    SkillMarkdownRenderer.parseAuthored(readString(authored));
            if (parsed.frontmatter() != null && !parsed.frontmatter().equals(frontmatter)) {
                Context.getCurrentLogger().warning("The frontmatter of the authored SKILL.md of"
                        + " skill '" + name + "' differs from the spec; the spec wins.");
            }
            body = parsed.body();
        }
        byte[] skillMd = SkillMarkdownRenderer.render(frontmatter, body)
                .getBytes(StandardCharsets.UTF_8);

        String base = SCHEME + name + "/";
        long total = skillMd.length;
        for (Path file : published.values()) {
            total += size(file);
        }
        int count = published.size() + 1;
        if (count > MAX_FILES || total > MAX_BYTES) {
            Context.getCurrentLogger().warning("Skill '" + name + "' is not served: it has "
                    + count + " files and " + total + " bytes, over the SEP-2640 limits of "
                    + MAX_FILES + " files and " + MAX_BYTES + " bytes per skill.");
            return null;
        }

        List<SkillFile> files = new ArrayList<>();
        files.add(file(SKILL_FILE, base, MARKDOWN, skillMd));
        for (Map.Entry<String, Path> entry : published.entrySet()) {
            files.add(file(entry.getKey(), base,
                    ResourceHandler.mimeTypeFromExtension(entry.getKey()),
                    readBytes(entry.getValue())));
        }
        return new SkillManifest(name, base + SKILL_FILE, frontmatter,
                SkillMarkdownRenderer.resourceMeta(skill), files);
    }

    private static Path resolveLocation(String name, String location) {
        try {
            Path path = Paths.get(URI.create(location));
            if (!Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("The location of skill '" + name + "' ("
                        + location + ") is not a readable directory.");
            }
            return path.toRealPath();
        } catch (IOException | IllegalArgumentException | FileSystemNotFoundException e) {
            throw new IllegalStateException("The location of skill '" + name + "' (" + location
                    + ") is not a valid file:/// directory: " + e.getMessage(), e);
        }
    }

    /**
     * Walk a skill location and return its publishable files, keyed and sorted by relative path.
     * The root {@code SKILL.md} is left out: the rendered one replaces it.
     */
    static Map<String, Path> publishedFiles(String name, Path root) {
        Map<String, Path> files = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    rejectSymlink(name, root, dir, attrs);
                    return isExcluded(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    rejectSymlink(name, root, file, attrs);
                    if (!attrs.isRegularFile() || isExcluded(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.equals(SKILL_FILE)) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (String segment : relative.split("/")) {
                        if (!SAFE_SEGMENT.matcher(segment).matches()) {
                            throw new IllegalStateException("File '" + relative + "' of skill '"
                                    + name + "' has a name that cannot be published as a "
                                    + SCHEME + " URI (letters, digits, '.', '_' and '-' only).");
                        }
                    }
                    files.put(relative, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the location of skill '" + name + "': "
                    + e.getMessage(), e);
        }
        return files;
    }

    /**
     * @return {@code true} for names that are never published: dotfiles and dot-directories,
     *         editor backups and temporary files.
     */
    static boolean isExcluded(String fileName) {
        return fileName.startsWith(".") || fileName.endsWith("~") || fileName.endsWith(".bak")
                || fileName.endsWith(".swp") || fileName.endsWith(".swo")
                || (fileName.length() > 1 && fileName.startsWith("#") && fileName.endsWith("#"));
    }

    private static void rejectSymlink(String name, Path root, Path path,
            BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink() || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Skill '" + name + "' contains a symbolic link ("
                    + root.relativize(path).toString().replace('\\', '/')
                    + "); symbolic links are never published.");
        }
    }

    private static SkillFile file(String path, String base, String mimeType, byte[] bytes) {
        return new SkillFile(path, base + path, mimeType, bytes, digest(bytes));
    }

    /** @return {@code sha256:} followed by the lowercase hex SHA-256 of the bytes. */
    static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readString(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }
}
