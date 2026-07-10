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
package io.ikanos.engine.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Resolves relative file paths within a {@code file:///} URI directory root, with path traversal
 * protection.
 *
 * <p>Used by both the Skill adapter and the Script step executor to validate that resolved paths
 * remain within the declared location directory. Each path segment is validated against an allowlist
 * and the final resolved path is checked for prefix containment.</p>
 */
public final class SafePathResolver {

    /** Allowed characters in a single path segment — no {@code ..}, no special characters. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private SafePathResolver() {}

    /**
     * Resolves {@code file} relative to the given {@code locationUri} and validates that the
     * resolved path stays within the location root (path traversal protection).
     *
     * @param locationUri {@code file:///} URI of the directory root
     * @param file        relative file path (e.g. {@code "script.js"} or
     *                    {@code "lib/helpers.js"})
     * @return the resolved absolute path
     * @throws SecurityException if any path segment is unsafe or the resolved path escapes the root
     */
    public static Path resolveAndValidate(String locationUri, String file) {
        try {
            URI uri;
            try {
                uri = URI.create(locationUri);
            } catch (IllegalArgumentException e) {
                throw new SecurityException(
                        "Invalid location URI: " + locationUri, e);
            }
            if (!"file".equals(uri.getScheme())) {
                throw new SecurityException(
                        "Location URI must use the file:/// scheme: " + locationUri);
            }
            Path root = Paths.get(uri).normalize().toAbsolutePath();

            // When the root directory exists, canonicalize via toRealPath() for symlink
            // protection. When it does not exist (e.g. skill location not yet provisioned),
            // fall back to the normalized absolute path — callers handle the missing file
            // gracefully (e.g. returning 404).
            Path effectiveRoot;
            if (Files.exists(root)) {
                effectiveRoot = root.toRealPath();
                if (!Files.isDirectory(effectiveRoot)) {
                    throw new SecurityException(
                            "Location root is not a directory: " + locationUri);
                }
            } else {
                effectiveRoot = root;
            }

            Path relPath = Paths.get(file);
            for (int i = 0; i < relPath.getNameCount(); i++) {
                String segment = relPath.getName(i).toString();
                if (!SAFE_SEGMENT.matcher(segment).matches()) {
                    throw new SecurityException(
                            "Unsafe path segment in request: " + segment);
                }
            }
            Path resolved = effectiveRoot.resolve(relPath).normalize().toAbsolutePath();
            if (!resolved.startsWith(effectiveRoot)) {
                throw new SecurityException("Path traversal attempt detected");
            }
            if (Files.exists(resolved)) {
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(effectiveRoot)) {
                    throw new SecurityException(
                            "Symlink escape detected: resolved path leaves the root directory");
                }
                return realResolved;
            }
            return resolved;
        } catch (IOException e) {
            throw new SecurityException(
                    "Cannot resolve real path for location '" + locationUri
                            + "': " + e.getMessage(),
                    e);
        }
    }

}
