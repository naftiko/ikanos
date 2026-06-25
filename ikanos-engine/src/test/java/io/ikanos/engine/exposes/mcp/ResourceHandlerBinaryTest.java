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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.routing.Router;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.Capability;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Tests for the dynamic MCP resource binary read path (capability-binary-content.md §4.5 / §8.3,
 * Phase 4). A dynamic resource backed by a consumed operation with {@code outputRawFormat: binary}
 * must return a base64 {@code blob} (BlobResourceContents) instead of UTF-8 text.
 */
class ResourceHandlerBinaryTest {

    /** Minimal JPEG header (SOI + APP0/JFIF) followed by EOI. */
    private static final byte[] JPEG_BYTES = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
            0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    void readShouldReturnBlobForBinaryDynamicResource() throws Exception {
        int port = findFreePort();
        Component server = createBinaryServer(port, JPEG_BYTES, MediaType.IMAGE_JPEG, null);
        server.start();

        try {
            Capability capability = capabilityFromYaml(binaryConsumesYaml(port, null));
            ResourceHandler handler = handlerWith(capability, "image/jpeg", "1MiB");

            List<ResourceHandler.ResourceContent> contents =
                    handler.read("photos://library/p-1/bytes");

            assertEquals(1, contents.size());
            ResourceHandler.ResourceContent c = contents.get(0);
            assertNull(c.text, "binary resource must not carry text");
            assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), c.blob);
            assertEquals("image/jpeg", c.mimeType);
        } finally {
            server.stop();
        }
    }

    @Test
    void readShouldFallBackToUpstreamMediaTypeWhenSpecMimeTypeMissing() throws Exception {
        int port = findFreePort();
        Component server = createBinaryServer(port, JPEG_BYTES, MediaType.IMAGE_PNG, null);
        server.start();

        try {
            Capability capability = capabilityFromYaml(binaryConsumesYaml(port, null));
            // No mimeType on the resource spec -> resolved upstream type (image/png) wins.
            ResourceHandler handler = handlerWith(capability, null, null);

            List<ResourceHandler.ResourceContent> contents =
                    handler.read("photos://library/p-1/bytes");

            assertEquals("image/png", contents.get(0).mimeType);
            assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), contents.get(0).blob);
        } finally {
            server.stop();
        }
    }

    @Test
    void readShouldThrowWhenBinaryResponseExceedsMaxBinarySize() throws Exception {
        int port = findFreePort();
        byte[] big = new byte[4096];
        Component server = createBinaryServer(port, big, MediaType.IMAGE_JPEG, "1KiB");
        server.start();

        try {
            Capability capability = capabilityFromYaml(binaryConsumesYaml(port, "1KiB"));
            ResourceHandler handler = handlerWith(capability, "image/jpeg", null);

            OperationStepExecutor.BinarySizeExceededException ex = assertThrows(
                    OperationStepExecutor.BinarySizeExceededException.class,
                    () -> handler.read("photos://library/p-1/bytes"));
            assertTrue(ex.getMaxBytes() > 0);
        } finally {
            server.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** Build a handler with a single dynamic binary resource bound to {@code photos.download}. */
    private ResourceHandler handlerWith(Capability capability, String mimeType,
            String unusedMaxBinarySize) {
        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName("photo-bytes");
        spec.setUri("photos://library/{id}/bytes");
        spec.setCall(new ServerCallSpec("photos.download"));
        if (mimeType != null) {
            spec.setMimeType(mimeType);
        }

        LinkedHashMap<String, McpServerResourceSpec> specMap = new LinkedHashMap<>();
        specMap.put(spec.getName(), spec);
        return new ResourceHandler(capability, specMap, "photos");
    }

    private String binaryConsumesYaml(int port, String maxBinarySize) {
        StringBuilder sb = new StringBuilder();
        sb.append("ikanos: \"").append(schemaVersion).append("\"\n");
        sb.append("capability:\n");
        sb.append("  exposes:\n");
        sb.append("  - type: rest\n");
        sb.append("    address: localhost\n");
        sb.append("    port: 0\n");
        sb.append("    namespace: dummy\n");
        sb.append("    resources:\n");
        sb.append("      dummy:\n");
        sb.append("        path: /dummy\n");
        sb.append("        operations:\n");
        sb.append("          dummy:\n");
        sb.append("            method: GET\n");
        sb.append("  consumes:\n");
        sb.append("  - namespace: photos\n");
        sb.append("    type: http\n");
        sb.append("    baseUri: \"http://localhost:").append(port).append("\"\n");
        sb.append("    resources:\n");
        sb.append("      photoBytes:\n");
        sb.append("        path: /photos/binary\n");
        sb.append("        operations:\n");
        sb.append("          download:\n");
        sb.append("            method: GET\n");
        sb.append("            outputRawFormat: binary\n");
        if (maxBinarySize != null) {
            sb.append("            maxBinarySize: \"").append(maxBinarySize).append("\"\n");
        }
        return sb.toString();
    }

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static Component createBinaryServer(int port, byte[] bytes, MediaType mediaType,
            String unused) {
        Component component = new Component();
        component.getServers().add(Protocol.HTTP, port);
        component.getDefaultHost().attach(new Application() {
            @Override
            public Restlet createInboundRoot() {
                Router router = new Router(getContext());
                router.attach("/photos/binary", new Restlet() {
                    @Override
                    public void handle(Request request, Response response) {
                        response.setStatus(Status.SUCCESS_OK);
                        response.setEntity(new ByteArrayRepresentation(bytes, mediaType));
                    }
                });
                return router;
            }
        });
        return component;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
