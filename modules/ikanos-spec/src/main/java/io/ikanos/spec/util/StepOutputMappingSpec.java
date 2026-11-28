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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Step Output Mapping Specification Element.
 *
 * Maps a step output field to a target name in the composite response. Used in orchestrated mode to
 * assemble outputs from multiple steps into a single result.
 *
 * <p>An entry may list {@code mappingSets} of the unit's {@code definitions} instead of mapping
 * one value (#1150): {@code target} and {@code value} are then the roots of the set's paths, and
 * the definitions expander replaces the entry with one entry per path at load time.</p>
 */
public class StepOutputMappingSpec {

    private volatile String target;
    private volatile String value;

    // As Polychro reads it (the JSON Schema wants a list of names): a single name reads as a
    // one-name list, and no value as no list
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private final List<String> mappingSets = new CopyOnWriteArrayList<>();

    public StepOutputMappingSpec() {}

    public StepOutputMappingSpec(String target, String value) {
        this.target = target;
        this.value = value;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /**
     * @return the names of the mapping sets whose paths this entry roots, in order; empty for an
     *         entry that maps one value
     */
    public List<String> getMappingSets() {
        return mappingSets;
    }

    public void setMappingSets(List<String> sets) {
        mappingSets.clear();
        if (sets != null) {
            for (String set : sets) {
                // A null item names a set by its text, as Polychro reads it: an unknown one
                mappingSets.add(set != null ? set : "null");
            }
        }
    }
}
