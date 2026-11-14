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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSearchProvidersTest {

    private static ToolSearchIndexFactory provider(String type) {
        return new ToolSearchIndexFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public ToolSearchIndex create(List<ToolSearchDocument> documents) {
                return query -> List.of();
            }
        };
    }

    @Test
    void findShouldReturnNothingWhenNoModuleIsInstalled() {
        assertTrue(ToolSearchProviders.find().isEmpty(),
                "the engine test classpath has no tool search module");
    }

    @Test
    void firstShouldReturnNothingWithoutProviders() {
        assertTrue(ToolSearchProviders.first(List.of()).isEmpty());
    }

    @Test
    void firstShouldReturnTheOnlyProvider() {
        ToolSearchIndexFactory only = provider("only");

        assertSame(only, ToolSearchProviders.first(List.of(only)).orElseThrow());
    }

    @Test
    void firstShouldPickTheFirstOfSeveralProviders() {
        ToolSearchIndexFactory first = provider("first");

        assertSame(first,
                ToolSearchProviders.first(List.of(first, provider("second"))).orElseThrow());
    }
}
