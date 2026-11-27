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
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Info Specification Element
 */
public class InfoSpec {

    private volatile String display;

    private volatile String description;

    private final List<String> tags;

    private volatile String created;

    private volatile String modified;

    private volatile String version;

    private final List<StakeholderSpec> stakeholders;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String publisher;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String websiteUrl;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String repositoryUrl;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<IconSpec> icons = new CopyOnWriteArrayList<>();

    public InfoSpec(String display, String description, String created, String modified) {
        this.display = display;
        this.description = description;
        this.tags = new CopyOnWriteArrayList<>();
        this.created = created;
        this.modified = modified;
        this.stakeholders = new CopyOnWriteArrayList<>();   
    }

    public InfoSpec() {
        this(null, null, null, null);
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<StakeholderSpec> getStakeholders() {
        return stakeholders;
    }

    /**
     * @return the reverse-DNS publisher prefix, such as {@code io.naftiko}, that names the
     *         capability's MCP Server Cards {@code <publisher>/<namespace>}, or {@code null}
     */
    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    /** @return the capability's home page, or {@code null} */
    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    /** @return the URL of the capability's source repository, or {@code null} */
    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    /** @return the capability's icons; empty when none are declared */
    public List<IconSpec> getIcons() {
        return icons;
    }

}
