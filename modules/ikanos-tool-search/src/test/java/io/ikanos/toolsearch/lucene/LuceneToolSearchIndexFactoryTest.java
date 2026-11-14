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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.exposes.mcp.search.ToolSearchDocument;
import io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory;
import io.ikanos.engine.exposes.mcp.search.ToolSearchProviders;
import io.ikanos.engine.exposes.mcp.search.ToolSearchQuery;

class LuceneToolSearchIndexFactoryTest {

    @Test
    void toolSearchProvidersShouldDiscoverTheLuceneFactory() {
        ToolSearchIndexFactory factory = ToolSearchProviders.find().orElseThrow();

        assertInstanceOf(LuceneToolSearchIndexFactory.class, factory);
    }

    @Test
    void typeShouldBeLucene() {
        assertEquals("lucene", new LuceneToolSearchIndexFactory().type());
    }

    @Test
    void createShouldBuildASearchableIndex() {
        List<String> names = new LuceneToolSearchIndexFactory()
                .create(List.of(new ToolSearchDocument("get-weather", null,
                        "Get the weather forecast.", List.of(), "", true)))
                .search(new ToolSearchQuery("forecast", Set.of(), false, 5));

        assertEquals(List.of("get-weather"), names);
    }
}
