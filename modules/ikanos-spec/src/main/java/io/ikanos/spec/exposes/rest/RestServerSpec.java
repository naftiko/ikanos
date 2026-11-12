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
package io.ikanos.spec.exposes.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ValueDeserializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.DefinitionsSpec;
import io.ikanos.spec.exposes.ServerSpec;

/** Web API Server Specification Element */
@JsonDeserialize(using = ValueDeserializer.None.class)
public class RestServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String namespace;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String maxBinarySize;

    /** Shared input parameter sets and schemas, visible to this namespace only (#1000). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<DefinitionsSpec> definitions = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = RestServerResourceMapDeserializer.class)
    private final Map<String, RestServerResourceSpec> resources =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public RestServerSpec() {
        this(null, 0, null);
    }

    public RestServerSpec(String address, int port, String namespace) {
        super("rest", address, port);
        this.namespace = namespace;
    }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    /**
     * @return the adapter-level {@code maxBinarySize} sized string (e.g. {@code "25MiB"}), or
     *         {@code null} when none is declared (engine default applies). See
     *         {@code blueprints/capability-binary-content.md} §4.7.
     */
    public String getMaxBinarySize() { return maxBinarySize; }
    public void setMaxBinarySize(String maxBinarySize) { this.maxBinarySize = maxBinarySize; }

    /**
     * @return the shared definitions of this namespace, or {@code null} when none are declared.
     *         Expanded at load time into the resources and operations that use them (#1000).
     */
    public DefinitionsSpec getDefinitions() { return definitions.get(); }
    public void setDefinitions(DefinitionsSpec definitions) { this.definitions.set(definitions); }

    public Map<String, RestServerResourceSpec> getResources() { return resources; }

    public void setResources(Map<String, RestServerResourceSpec> resources) {
        if (resources == null) return;
        synchronized (this.resources) {
            this.resources.clear();
            this.resources.putAll(resources);
        }
    }
}
