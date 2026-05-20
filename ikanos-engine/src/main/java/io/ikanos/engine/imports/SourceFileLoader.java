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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Load and parse a YAML source file, returning a cached result if the same path has
     * already been loaded during this session.
     *
     * @param absolutePath normalized absolute path to the source file
     * @return the parsed {@link IkanosSpec}
     * @throws IOException if the file cannot be read or parsed
     */
    public IkanosSpec load(Path absolutePath) throws IOException {
        IkanosSpec cached = cache.get(absolutePath);
        if (cached != null) {
            return cached;
        }

        try {
            IkanosSpec spec = yamlMapper.readValue(absolutePath.toFile(), IkanosSpec.class);
            cache.put(absolutePath, spec);
            return spec;
        } catch (IOException e) {
            throw new IOException(
                    "Failed to load source file: " + absolutePath + " - " + e.getMessage(), e);
        }
    }

    /** Returns the number of cached entries (useful for testing). */
    int cacheSize() {
        return cache.size();
    }
}
