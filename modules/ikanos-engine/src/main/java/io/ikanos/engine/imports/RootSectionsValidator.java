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

import java.util.List;

import io.ikanos.spec.IkanosSpec;

/**
 * Rejects root {@code consumes}, {@code exposes}, {@code aggregates} and {@code binds} in a
 * capability document.
 *
 * <p>Those root sections belong to standalone shared files. A capability document keeps them under
 * {@code capability}, and the engine reads them only there. The JSON Schema says the same, but a
 * capability is not validated against it when it is served, so without this check a root section
 * would be ignored silently: root {@code binds}, for example, would leave every credential it
 * declares unbound (#955).</p>
 */
public final class RootSectionsValidator {

    /** The sections a capability document keeps under {@code capability}, never at its root. */
    public static final List<String> SECTIONS =
            List.of("consumes", "exposes", "aggregates", "binds");

    private RootSectionsValidator() {
    }

    /**
     * Validate that a capability document declares no root section.
     *
     * @param spec the parsed document, before imports are resolved
     * @throws IllegalArgumentException naming the root section and where to move it
     */
    public static void validate(IkanosSpec spec) {
        if (spec == null || spec.getCapability() == null) {
            return;
        }
        reject("consumes", spec.getConsumes());
        reject("exposes", spec.getExposes());
        reject("aggregates", spec.getAggregates());
        reject("binds", spec.getBinds());
    }

    /**
     * The error for a root section found in a capability document, shared with
     * {@code ikanos validate}.
     *
     * @param section one of {@link #SECTIONS}
     * @return the message, naming where the section belongs
     */
    public static String message(String section) {
        return "Root '" + section
                + "' is not allowed in a capability document: move it under 'capability."
                + section + "'. Root '" + section + "' is only valid in a standalone shared file.";
    }

    private static void reject(String section, List<?> entries) {
        if (entries != null && !entries.isEmpty()) {
            throw new IllegalArgumentException(message(section));
        }
    }
}
