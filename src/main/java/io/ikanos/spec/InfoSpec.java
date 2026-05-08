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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Info Specification Element
 */
public class InfoSpec {

    private volatile String label;

    private volatile String description;

    private final List<String> tags;

    private volatile String created;

    private volatile String modified;

    private final List<StakeholderSpec> stakeholders;

    public InfoSpec(String label, String description, String created, String modified) {
        this.label = label;
        this.description = description;
        this.tags = new CopyOnWriteArrayList<>();
        this.created = created;
        this.modified = modified;
        this.stakeholders = new CopyOnWriteArrayList<>();   
    }

    public InfoSpec() {
        this(null, null, null, null);
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }   

    public String getModified() {
        return modified;
    }

    public void setModified(String modified) {
        this.modified = modified;
    }

    public List<StakeholderSpec> getStakeholders() {
        return stakeholders;
    }

}
