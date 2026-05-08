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
package io.ikanos.spec.aggregates;

/**
 * Transport-neutral behavioral metadata for an invocable unit. Used for design-time tooling and
 * adapter derivations (e.g. MCP hints).
 */
public class SemanticsSpec {

    private Boolean safe;
    private Boolean idempotent;
    private Boolean cacheable;

    public SemanticsSpec() {}

    public SemanticsSpec(Boolean safe, Boolean idempotent, Boolean cacheable) {
        this.safe = safe;
        this.idempotent = idempotent;
        this.cacheable = cacheable;
    }

    public Boolean getSafe() {
        return safe;
    }

    public void setSafe(Boolean safe) {
        this.safe = safe;
    }

    public Boolean getIdempotent() {
        return idempotent;
    }

    public void setIdempotent(Boolean idempotent) {
        this.idempotent = idempotent;
    }

    public Boolean getCacheable() {
        return cacheable;
    }

    public void setCacheable(Boolean cacheable) {
        this.cacheable = cacheable;
    }

}
