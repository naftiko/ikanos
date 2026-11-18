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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
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
    void discoveryShouldFailWithAClearReasonWhenLuceneIsAbsent() throws Exception {
        // The plain module jar without Lucene: this module's classes, and no org.apache.lucene
        URL moduleClasses = LuceneToolSearchIndexFactory.class.getProtectionDomain()
                .getCodeSource().getLocation();
        ClassLoader withoutLucene = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("org.apache.lucene.")
                        || name.startsWith("io.ikanos.toolsearch.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        try (URLClassLoader plainJar = new URLClassLoader(new URL[] {moduleClasses},
                withoutLucene)) {
            ServiceConfigurationError error = assertThrows(ServiceConfigurationError.class,
                    () -> ServiceLoader.load(ToolSearchIndexFactory.class, plainJar)
                            .iterator().next());

            Throwable cause = error.getCause();
            assertInstanceOf(IllegalStateException.class, cause, String.valueOf(cause));
            assertTrue(cause.getMessage().contains("-all jar"), cause.getMessage());
        }
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
