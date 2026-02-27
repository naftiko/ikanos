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
package io.naftiko;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.engine.consumes.ClientAdapter;
import io.naftiko.engine.consumes.HttpClientAdapter;
import io.naftiko.engine.exposes.ApiServerAdapter;
import io.naftiko.engine.exposes.McpServerAdapter;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.HttpClientSpec;
import io.naftiko.spec.exposes.ApiServerSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Main Capability class that initializes and manages adapters based on configuration
 */
public class Capability {

    private volatile NaftikoSpec spec;
    private volatile List<ServerAdapter> serverAdapters;
    private volatile List<ClientAdapter> clientAdapters;

    public Capability(NaftikoSpec spec) {
        this.spec = spec;

        // Initialize client adapters first
        this.clientAdapters = new CopyOnWriteArrayList<>();

        // Then initialize server adapters with reference to source adapters
        this.serverAdapters = new CopyOnWriteArrayList<>();

        if (spec.getCapability().getExposes().isEmpty()) {
            throw new IllegalArgumentException("Capability must expose at least one endpoint.");
        }

        for (ServerSpec serverSpec : spec.getCapability().getExposes()) {
            if ("api".equals(serverSpec.getType())) {
                this.serverAdapters.add(new ApiServerAdapter(this, (ApiServerSpec) serverSpec));
            } else if ("mcp".equals(serverSpec.getType())) {
                this.serverAdapters.add(new McpServerAdapter(this, (McpServerSpec) serverSpec));
            }
        }

        for (ClientSpec clientSpec : spec.getCapability().getConsumes()) {
            if ("http".equals(clientSpec.getType())) {
                this.clientAdapters.add(new HttpClientAdapter(this, (HttpClientSpec) clientSpec));
            }
        }
    }

    public NaftikoSpec getSpec() {
        return spec;
    }

    public void setSpec(NaftikoSpec config) {
        this.spec = config;
    }

    public List<ClientAdapter> getClientAdapters() {
        return clientAdapters;
    }

    public List<ServerAdapter> getServerAdapters() {
        return serverAdapters;
    }

    public void start() throws Exception {
        for (ClientAdapter adapter : getClientAdapters()) {
            adapter.start();
        }

        for (ServerAdapter adapter : getServerAdapters()) {
            adapter.start();
        }
    }

    public void stop() throws Exception {
        for (ServerAdapter adapter : getServerAdapters()) {
            adapter.stop();
        }

        for (ClientAdapter adapter : getClientAdapters()) {
            adapter.stop();
        }
    }

    /**
     * Luanch the capability, reading its configuration from local NAFTIKO.yaml file unless a
     * specific name is provided.
     * 
     * @param args The optional part and name of the capability configuration file.
     */
    public static void main(String[] args) {
        // Determine file path: Argument if provided, otherwise default
        String filePath = (args.length > 0) ? args[0] : "naftiko.yaml";

        File file = new File(filePath);
        System.out.println("Reading configuration from: " + file.getAbsolutePath());

        // Read the configuraton file
        if (file.exists()) {
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                // Ignore unknown properties to handle potential Restlet framework classes
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
                Capability capability = new Capability(spec);
                capability.start();
                System.out.println("Capability started successfully.");
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error reading file: " + e.getMessage());
            }
        } else {
            System.err.println("Error: File not found at " + filePath);
            System.exit(1);
        }
    }

}
