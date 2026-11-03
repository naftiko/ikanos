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
package io.ikanos.spec.util;

import java.util.Map;
import java.util.SequencedMap;

/**
 * Guards the order of a {@code steps:} map.
 *
 * <p>Until {@code steps:} becomes an ordered list, the document order of the map is the order in
 * which the steps run. JSON and YAML do not guarantee key order, so the order is kept by the
 * loader (Jackson fills a {@code LinkedHashMap}) and checked where a map enters a spec object: a
 * map without a defined iteration order, such as a {@code HashMap} built by an embedder, is
 * rejected instead of running its steps in hash order.</p>
 */
public final class StepOrder {

    private StepOrder() {
    }

    /**
     * Returns {@code steps} when its iteration order is defined, or {@code null} when it is
     * {@code null}.
     *
     * @throws IllegalArgumentException when {@code steps} is not a {@link SequencedMap}
     */
    public static <V> Map<String, V> requireOrdered(Map<String, V> steps) {
        if (steps != null && !(steps instanceof SequencedMap)) {
            throw new IllegalArgumentException("Steps run in the order they are declared, so they "
                    + "need a map with a defined order (such as a LinkedHashMap), not a "
                    + steps.getClass().getName());
        }
        return steps;
    }
}
