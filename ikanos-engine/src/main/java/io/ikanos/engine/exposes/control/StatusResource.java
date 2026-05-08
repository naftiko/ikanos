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
package io.ikanos.engine.exposes.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.observability.TelemetryBootstrap;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.util.VersionHelper;
import io.opentelemetry.api.OpenTelemetry;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * Status endpoint. Returns capability metadata, engine version, uptime, OTel status, and adapter
 * summary.
 */
public class StatusResource extends ServerResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Get("json")
    public Representation getStatusInfo() {
        Capability capability =
                (Capability) getContext().getAttributes().get("capability");

        ObjectNode root = MAPPER.createObjectNode();

        // Capability info
        ObjectNode capNode = MAPPER.createObjectNode();
        if (capability.getSpec().getInfo() != null) {
            capNode.put("label", capability.getSpec().getInfo().getLabel());
        }
        capNode.put("specVersion", capability.getSpec().getIkanos());
        root.set("capability", capNode);

        // Engine info
        ObjectNode engineNode = MAPPER.createObjectNode();
        engineNode.put("version", VersionHelper.getSchemaVersion());
        engineNode.put("java", System.getProperty("java.version"));
        engineNode.put("native", isNativeImage());
        root.set("engine", engineNode);

        // Uptime
        long startTimeMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        long uptimeMillis = System.currentTimeMillis() - startTimeMillis;
        root.put("uptime", Duration.ofMillis(uptimeMillis).toString());

        // OTel status
        ObjectNode otelNode = MAPPER.createObjectNode();
        OpenTelemetry otel = TelemetryBootstrap.get().getOpenTelemetry();
        boolean otelActive = otel != OpenTelemetry.noop();
        otelNode.put("status", otelActive ? "active" : "inactive");
        root.set("otel", otelNode);

        // Adapter summary
        ArrayNode adaptersNode = MAPPER.createArrayNode();
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            ServerSpec spec = adapter.getSpec();
            ObjectNode adapterNode = MAPPER.createObjectNode();
            adapterNode.put("type", spec.getType());

            String namespace = HealthReadyResource.getNamespace(spec);
            if (namespace != null) {
                adapterNode.put("namespace", namespace);
            }
            if (spec.getPort() > 0) {
                adapterNode.put("port", spec.getPort());
            }
            boolean started = adapter.getServer() != null && adapter.getServer().isStarted();
            adapterNode.put("state", started ? "started" : "stopped");
            adaptersNode.add(adapterNode);
        }
        root.set("adapters", adaptersNode);

        return new StringRepresentation(root.toString(), MediaType.APPLICATION_JSON);
    }

    static boolean isNativeImage() {
        return "Substrate VM".equals(System.getProperty("java.vm.name"));
    }
}
