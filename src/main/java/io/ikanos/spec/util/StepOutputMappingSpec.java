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

/**
 * Step Output Mapping Specification Element.
 *
 * Maps a step output field to a target name in the composite response. Used in orchestrated mode to
 * assemble outputs from multiple steps into a single result.
 */
public class StepOutputMappingSpec {

    private volatile String targetName;
    private volatile String value;

    public StepOutputMappingSpec() {}

    public StepOutputMappingSpec(String targetName, String value) {
        this.targetName = targetName;
        this.value = value;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
