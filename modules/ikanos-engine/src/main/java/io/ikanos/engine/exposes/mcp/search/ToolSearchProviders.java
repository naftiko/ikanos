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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Level;
import org.restlet.Context;

/**
 * Finds the {@link ToolSearchIndexFactory} installed on the classpath, if any.
 */
public final class ToolSearchProviders {

    private ToolSearchProviders() {
        // Utility class — no instances.
    }

    /**
     * Discovers the tool search provider with the class loader of the SPI type, as the tunnel
     * bootstrap does, so discovery works in the shaded CLI jar and under a non-default context
     * class loader. The first provider wins; when several are installed, a warning names them.
     *
     * @return the provider, or empty when no tool search module is installed
     */
    public static Optional<ToolSearchIndexFactory> find() {
        return first(ServiceLoader.load(ToolSearchIndexFactory.class,
                ToolSearchIndexFactory.class.getClassLoader()));
    }

    static Optional<ToolSearchIndexFactory> first(Iterable<ToolSearchIndexFactory> providers) {
        List<ToolSearchIndexFactory> found = new ArrayList<>();
        providers.forEach(found::add);
        if (found.size() > 1) {
            Context.getCurrentLogger().log(Level.WARNING,
                    "Several MCP tool search providers are installed: {0}. Using {1}.",
                    new Object[] {found.stream().map(f -> f.getClass().getName()).toList(),
                        found.getFirst().getClass().getName()});
        }
        return found.stream().findFirst();
    }
}
