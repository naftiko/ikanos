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
package io.ikanos.engine.imports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.spec.IkanosSpec;

/**
 * Shared loader for standalone YAML source files referenced by import directives.
 *
 * <p>A single instance is shared across the four section resolvers during one capability load,
 * so a file imported by multiple sections (e.g. {@code consumes} and {@code aggregates} from
 * the same bundle) is parsed only once.</p>
 *
 * <p>The cache is keyed by the <strong>normalized absolute path</strong> of each source file.</p>
 */
public class SourceFileLoader {

    private final ObjectMapper yamlMapper;
    private final Map<Path, IkanosSpec> cache;

    public SourceFileLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Load and parse a YAML source file, returning a cached result if the same path has
     * already been loaded during this session.
     *
     * @param absolutePath absolute path to the source file (normalized internally for cache safety)
     * @return the parsed {@link IkanosSpec}
     * @throws IOException if the file cannot be read or parsed
     */
    public IkanosSpec load(Path absolutePath) throws IOException {
        Path key = absolutePath.normalize().toAbsolutePath();

        try {
            return cache.computeIfAbsent(key, p -> {
                try {
                    return yamlMapper.readValue(p.toFile(), IkanosSpec.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new IOException(
                    "Failed to load source file: " + key + " - " + e.getCause().getMessage(),
                    e.getCause());
        }
    }

    /**
     * Deep-copy an object via Jackson round-trip (serialize → deserialize).
     *
     * <p>Used by the import resolver to create independent copies of resolved entries so that
     * mutations in one capability do not affect another capability importing the same source.</p>
     *
     * @param <R>  the result type
     * @param src  the object to deep-copy
     * @param type the target type for deserialization
     * @return an independent deep copy
     * @throws IOException if serialization or deserialization fails
     */
    <R> R deepCopy(Object src, Class<R> type) throws IOException {
        byte[] bytes = yamlMapper.writeValueAsBytes(src);
        return yamlMapper.readValue(bytes, type);
    }

    /** Returns the number of cached entries (useful for testing). */
    int cacheSize() {
        return cache.size();
    }
}
