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
package io.ikanos.spec.exposes.skill;

/**
 * Source reference for a derived skill tool.
 *
 * <p>Identifies a sibling {@code api} or {@code mcp} adapter by namespace and the specific
 * operation name (api) or tool name (mcp) to derive.</p>
 */
public class SkillToolFromSpec {

    private volatile String sourceNamespace;
    private volatile String action;

    public SkillToolFromSpec() {}

    public SkillToolFromSpec(String sourceNamespace, String action) {
        this.sourceNamespace = sourceNamespace;
        this.action = action;
    }

    public String getSourceNamespace() {
        return sourceNamespace;
    }

    public void setSourceNamespace(String sourceNamespace) {
        this.sourceNamespace = sourceNamespace;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
