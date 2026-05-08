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
package io.ikanos.spec;

/**
 * Stakeholder Specification Element
 */
public class StakeholderSpec {

    private volatile String role;

    private volatile String fullName;

    private volatile String email;

    public StakeholderSpec(String role, String fullName, String email) {
        this.role = role;
        this.fullName = fullName;
        this.email = email;
    }

    public StakeholderSpec() {
        this(null, null, null);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String label) {
        this.fullName = label;
    }

    public String getEmail() {
        return email;
    }
    
    public void setEmail(String description) {
        this.email = description;
    }

}
