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
package io.naftiko.engine.exposes.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.RestServerSpec;
import io.naftiko.spec.exposes.ServerSpec;
import io.naftiko.spec.exposes.SkillServerSpec;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

/**
 * Readiness probe. Returns {@code 200} when all business adapters are started,
 * {@code 503} when any adapter is not ready.
 */
public class HealthReadyResource extends ServerResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Get("json")
    public Representation getReady() {
        Capability capability =
                (Capability) getContext().getAttributes().get("capability");

        boolean allReady = true;
        ArrayNode adaptersNode = MAPPER.createArrayNode();

        for (ServerAdapter adapter : capability.getServerAdapters()) {
            ServerSpec spec = adapter.getSpec();
            ObjectNode adapterNode = MAPPER.createObjectNode();
            adapterNode.put("type", spec.getType());

            String namespace = getNamespace(spec);
            if (namespace != null) {
                adapterNode.put("namespace", namespace);
            }
            if (spec.getPort() > 0) {
                adapterNode.put("port", spec.getPort());
            }

            boolean started = adapter.getServer() != null && adapter.getServer().isStarted();
            adapterNode.put("state", started ? "started" : "stopped");

            if (!started && !"control".equals(spec.getType())) {
                allReady = false;
            }

            adaptersNode.add(adapterNode);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", allReady ? "UP" : "DEGRADED");
        root.set("adapters", adaptersNode);

        setStatus(allReady ? Status.SUCCESS_OK : Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
        return new StringRepresentation(root.toString(), MediaType.APPLICATION_JSON);
    }

    static String getNamespace(ServerSpec spec) {
        if (spec instanceof RestServerSpec rest) {
            return rest.getNamespace();
        } else if (spec instanceof McpServerSpec mcp) {
            return mcp.getNamespace();
        } else if (spec instanceof SkillServerSpec skill) {
            return skill.getNamespace();
        }
        return null;
    }
}
