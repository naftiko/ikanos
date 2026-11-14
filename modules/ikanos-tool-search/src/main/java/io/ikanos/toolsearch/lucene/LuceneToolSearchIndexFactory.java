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
package io.ikanos.toolsearch.lucene;

import java.util.List;
import io.ikanos.engine.exposes.mcp.search.ToolSearchDocument;
import io.ikanos.engine.exposes.mcp.search.ToolSearchIndex;
import io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory;

/**
 * The Lucene tool search provider, registered for {@link java.util.ServiceLoader} in
 * {@code META-INF/services/io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory}.
 */
public final class LuceneToolSearchIndexFactory implements ToolSearchIndexFactory {

    /** Required by {@link java.util.ServiceLoader}; has no side effect. */
    public LuceneToolSearchIndexFactory() {
        // Nothing to set up: the index is built on the first search.
    }

    @Override
    public String type() {
        return "lucene";
    }

    @Override
    public ToolSearchIndex create(List<ToolSearchDocument> documents) {
        return new LuceneToolSearchIndex(documents);
    }
}
