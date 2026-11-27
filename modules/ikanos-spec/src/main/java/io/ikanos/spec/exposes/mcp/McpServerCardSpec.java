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
package io.ikanos.spec.exposes.mcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ikanos.spec.IconSpec;

/**
 * MCP Server Card of an HTTP MCP server (SEP-2127). Its presence on an {@code mcp} exposes entry
 * turns the card on, at {@code GET <path>/server-card}. Every field is optional: the engine
 * generates the card from {@code info} and the exposes entry, and a field set here replaces the
 * generated value for this server only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpServerCardSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicReference<String> url = new AtomicReference<>();
    private final AtomicReference<String> title = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> websiteUrl = new AtomicReference<>();
    private final AtomicReference<String> repositoryUrl = new AtomicReference<>();
    private final AtomicReference<List<IconSpec>> icons = new AtomicReference<>();
    private final AtomicReference<Boolean> authenticated = new AtomicReference<>();

    /** @return the card name, else {@code <info.publisher>/<namespace>} is generated */
    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    /** @return the published server version, else {@code info.version} applies */
    public String getVersion() {
        return version.get();
    }

    public void setVersion(String version) {
        this.version.set(version);
    }

    /** @return the public endpoint URL, such as behind a gateway, else one is generated */
    public String getUrl() {
        return url.get();
    }

    public void setUrl(String url) {
        this.url.set(url);
    }

    /** @return the human-readable title, else {@code info.display} applies */
    public String getTitle() {
        return title.get();
    }

    public void setTitle(String title) {
        this.title.set(title);
    }

    /** @return the card description, else the exposes entry's {@code description} applies */
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    /** @return the home page, else {@code info.websiteUrl} applies */
    public String getWebsiteUrl() {
        return websiteUrl.get();
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl.set(websiteUrl);
    }

    /** @return the source repository URL, else {@code info.repositoryUrl} applies */
    public String getRepositoryUrl() {
        return repositoryUrl.get();
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl.set(repositoryUrl);
    }

    /** @return the icons, which replace {@code info.icons} whole, or {@code null} */
    public List<IconSpec> getIcons() {
        return icons.get();
    }

    public void setIcons(List<IconSpec> icons) {
        this.icons.set(icons != null ? List.copyOf(icons) : null);
    }

    /** @return the declared {@code authenticated} flag, or {@code null} when omitted */
    public Boolean getAuthenticated() {
        return authenticated.get();
    }

    public void setAuthenticated(Boolean authenticated) {
        this.authenticated.set(authenticated);
    }

    /**
     * @return {@code true} when the card sits behind the server's authentication; {@code false}
     *         (the default) for a public card
     */
    public boolean requiresAuthentication() {
        return Boolean.TRUE.equals(authenticated.get());
    }
}
