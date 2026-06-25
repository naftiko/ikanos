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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.routing.Router;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration tests for the REST binary-response runtime branch (capability-binary-content.md
 * §7.5, Phase 2). Proves that a REST operation backed by a consumed operation with
 * {@code outputRawFormat: binary} returns the raw upstream bytes with the resolved
 * {@code Content-Type}, and that the {@code maxBinarySize} cap is enforced.
 */
public class RestBinaryResponseIntegrationTest {

    /** Minimal JPEG header (SOI + APP0/JFIF) followed by EOI — enough to assert byte fidelity. */
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
    public void handleShouldReturnRawBytesWithDeclaredMediaTypeWhenConsumedOperationIsBinary()
            throws Exception {
        int port = findFreePort();
        Component upstream = createBinaryServer(port, JPEG_BYTES, MediaType.IMAGE_JPEG);
        upstream.start();

        try {
            Capability capability = capabilityFromYaml(binaryCapabilityYaml(port, null));
            Response response = invokeGetPhotoImage(capability);

            assertEquals(Status.SUCCESS_OK, response.getStatus());
            assertNotNull(response.getEntity());
            assertEquals("image/jpeg", response.getEntity().getMediaType().getName());
            assertArrayEquals(JPEG_BYTES, readBytes(response));
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleShouldPreferDeclaredOutputMediaTypeOverGenericUpstream() throws Exception {
        int port = findFreePort();
        // Upstream lies with octet-stream; the consumed op declares image/jpeg, which must win.
        Component upstream = createBinaryServer(port, JPEG_BYTES, MediaType.APPLICATION_OCTET_STREAM);
        upstream.start();

        try {
            Capability capability = capabilityFromYaml(binaryCapabilityYaml(port, null));
            Response response = invokeGetPhotoImage(capability);

            assertEquals(Status.SUCCESS_OK, response.getStatus());
            assertEquals("image/jpeg", response.getEntity().getMediaType().getName());
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleShouldReturnServerErrorWhenResponseExceedsMaxBinarySize() throws Exception {
        int port = findFreePort();
        byte[] big = new byte[4096];
        Component upstream = createBinaryServer(port, big, MediaType.IMAGE_JPEG);
        upstream.start();

        try {
            Capability capability = capabilityFromYaml(binaryCapabilityYaml(port, "1KiB"));
            Response response = invokeGetPhotoImage(capability);

            assertEquals(Status.SERVER_ERROR_INTERNAL, response.getStatus());
            assertNotNull(response.getEntity());
        } finally {
            upstream.stop();
        }
    }

    private Response invokeGetPhotoImage(Capability capability) {
        RestServerSpec serverSpec =
                (RestServerSpec) capability.getServerAdapters().get(0).getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, serverSpec,
                serverSpec.getResources().values().iterator().next());

        Request request = new Request(Method.GET, "http://localhost/photos/image");
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }

    private String binaryCapabilityYaml(int port, String maxBinarySize) {
        String capLine = maxBinarySize != null
                ? "\n              maxBinarySize: \"" + maxBinarySize + "\""
                : "";
        return """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "photos"
                      resources:
                        - path: "/photos/image"
                          name: "photo-image"
                          operations:
                            - method: "GET"
                              name: "get-photo-image"
                              call: "photos.download"
                              responseBinary:
                                status: 200
                                mediaType: "image/jpeg"
                                description: "Original image bytes"
                  consumes:
                    - type: "http"
                      namespace: "photos"
                      baseUri: "http://localhost:%d"
                      resources:
                        - path: "/photos/binary"
                          name: "photo-bytes"
                          operations:
                            - method: "GET"
                              name: "download"
                              outputRawFormat: "binary"
                              outputMediaType: "image/jpeg"%s
                """.formatted(schemaVersion, port, capLine);
    }

    private static byte[] readBytes(Response response) throws Exception {
        return response.getEntity().getStream().readAllBytes();
    }

    private static Component createBinaryServer(int port, byte[] bytes, MediaType mediaType)
            throws Exception {
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

    private static Capability capabilityFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        return new Capability(spec);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
