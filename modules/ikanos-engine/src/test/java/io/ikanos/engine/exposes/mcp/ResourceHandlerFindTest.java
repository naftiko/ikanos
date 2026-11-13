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

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;

/**
 * {@link ResourceHandler#find(String)}: which resource serves a URI.
 */
class ResourceHandlerFindTest {

    @Test
    void findShouldReturnNullWhenNoResourceMatches() {
        ResourceHandler handler = handler(dynamic("user", "data://users/{id}"));

        assertThat(handler.find("data://orders/1")).isNull();
    }

    @Test
    void findShouldMatchADynamicResourceTemplate() {
        McpServerResourceSpec user = dynamic("user", "data://users/{id}");

        assertThat(handler(user).find("data://users/42")).isSameAs(user);
    }

    @Test
    void findShouldMatchAFileUnderAStaticResource() {
        McpServerResourceSpec docs = staticResource("docs", "docs://guide");

        assertThat(handler(docs).find("docs://guide/intro.md")).isSameAs(docs);
    }

    @Test
    void findShouldReturnTheFirstDeclaredResourceWhenSeveralMatch() {
        McpServerResourceSpec first = dynamic("first", "data://items/{id}");
        McpServerResourceSpec second = dynamic("second", "data://items/{key}");

        assertThat(handler(first, second).find("data://items/7")).isSameAs(first);
    }

    static ResourceHandler handler(McpServerResourceSpec... specs) {
        Map<String, McpServerResourceSpec> resources = new LinkedHashMap<>();
        for (McpServerResourceSpec spec : specs) {
            resources.put(spec.getName(), spec);
        }
        return new ResourceHandler(null, resources, null);
    }

    static McpServerResourceSpec dynamic(String name, String uri) {
        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName(name);
        spec.setUri(uri);
        return spec;
    }

    static McpServerResourceSpec staticResource(String name, String uri) {
        McpServerResourceSpec spec = dynamic(name, uri);
        spec.setLocation("file:///tmp/" + name);
        return spec;
    }
}
