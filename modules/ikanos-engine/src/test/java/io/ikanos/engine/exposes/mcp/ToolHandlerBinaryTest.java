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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tests for the MCP tool binary-result branch (capability-binary-content.md §4.4 / §8.2, Phase 3).
 *
 * <p>Covers both the pure MIME-driven dispatch ({@link ToolHandler#buildBinaryContent}) and the
 * end-to-end {@code tools/call} path against a live upstream that returns raw bytes.</p>
 */
public class ToolHandlerBinaryTest {

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

    // ── Unit tests: MIME-driven dispatch ──────────────────────────────────────────────────────

    @Test
    public void buildBinaryContentShouldReturnImageContentForImageMime() {
        ToolHandler handler = new ToolHandler(null, Map.of(), "photos");
        byte[] bytes = {1, 2, 3, 4};

        McpSchema.Content content = handler.buildBinaryContent("get-photo", bytes, "image/jpeg");

        McpSchema.ImageContent image = assertInstanceOf(McpSchema.ImageContent.class, content);
        assertEquals("image/jpeg", image.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(bytes), image.data());
    }

    @Test
    public void buildBinaryContentShouldReturnAudioContentForAudioMime() {
        ToolHandler handler = new ToolHandler(null, Map.of(), "sounds");
        byte[] bytes = {5, 6, 7, 8};

        McpSchema.Content content = handler.buildBinaryContent("get-clip", bytes, "audio/mpeg");

        McpSchema.AudioContent audio = assertInstanceOf(McpSchema.AudioContent.class, content);
        assertEquals("audio/mpeg", audio.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(bytes), audio.data());
    }

    @Test
    public void buildBinaryContentShouldReturnEmbeddedResourceForOtherMime() {
        ToolHandler handler = new ToolHandler(null, Map.of(), "docs");
        byte[] bytes = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

        McpSchema.Content content = handler.buildBinaryContent("get-pdf", bytes,
                "application/pdf");

        McpSchema.EmbeddedResource embedded =
                assertInstanceOf(McpSchema.EmbeddedResource.class, content);
        McpSchema.BlobResourceContents blob =
                assertInstanceOf(McpSchema.BlobResourceContents.class, embedded.resource());
        assertEquals("application/pdf", blob.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(bytes), blob.blob());
        assertTrue(blob.uri().startsWith("ikanos://transient/docs/get-pdf/"),
                "transient URI should follow the ikanos://transient/{capability}/{tool}/{uuid} pattern");
    }

    @Test
    public void buildBinaryContentShouldFallBackToEmbeddedForOctetStream() {
        ToolHandler handler = new ToolHandler(null, Map.of(), null);
        byte[] bytes = {0, 1, 2};

        McpSchema.Content content = handler.buildBinaryContent("download", bytes,
                "application/octet-stream");

        McpSchema.EmbeddedResource embedded =
                assertInstanceOf(McpSchema.EmbeddedResource.class, content);
        McpSchema.BlobResourceContents blob =
                assertInstanceOf(McpSchema.BlobResourceContents.class, embedded.resource());
        assertTrue(blob.uri().startsWith("ikanos://transient/ikanos/download/"),
                "missing namespace should fall back to 'ikanos' in the transient URI");
    }

    // ── End-to-end: tools/call against a live binary upstream ─────────────────────────────────

    @Test
    public void handleToolCallShouldReturnImageContentForBinaryUpstream() throws Exception {
        int port = findFreePort();
        Component upstream = createBinaryServer(port, JPEG_BYTES, MediaType.IMAGE_JPEG);
        upstream.start();

        try {
            ToolHandler handler = handlerFromYaml(binaryToolCapabilityYaml(port, null));
            McpSchema.CallToolResult result =
                    handler.handleToolCall("get-photo", Map.of("id", "p-1"));

            assertEquals(Boolean.FALSE, result.isError());
            assertEquals(1, result.content().size());
            McpSchema.ImageContent image =
                    assertInstanceOf(McpSchema.ImageContent.class, result.content().get(0));
            assertEquals("image/jpeg", image.mimeType());
            assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), image.data());
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldReturnEmbeddedResourceForPdfUpstream() throws Exception {
        int port = findFreePort();
        byte[] pdf = "%PDF-1.7 binary".getBytes(StandardCharsets.US_ASCII);
        Component upstream =
                createBinaryServer(port, pdf, MediaType.valueOf("application/pdf"));
        upstream.start();

        try {
            ToolHandler handler =
                    handlerFromYaml(binaryToolCapabilityYaml(port, "application/pdf"));
            McpSchema.CallToolResult result =
                    handler.handleToolCall("get-photo", Map.of("id", "p-1"));

            assertEquals(Boolean.FALSE, result.isError());
            assertInstanceOf(McpSchema.EmbeddedResource.class, result.content().get(0));
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldReturnErrorWhenResponseExceedsMaxBinarySize() throws Exception {
        int port = findFreePort();
        byte[] big = new byte[4096];
        Component upstream = createBinaryServer(port, big, MediaType.IMAGE_JPEG);
        upstream.start();

        try {
            ToolHandler handler =
                    handlerFromYaml(binaryToolCapabilityYaml(port, null, "1KiB"));
            McpSchema.CallToolResult result =
                    handler.handleToolCall("get-photo", Map.of("id", "p-1"));

            assertEquals(Boolean.TRUE, result.isError());
            McpSchema.TextContent text =
                    assertInstanceOf(McpSchema.TextContent.class, result.content().get(0));
            assertTrue(text.text().contains("maxBinarySize"));
        } finally {
            upstream.stop();
        }
    }

    @Test
    public void handleToolCallShouldSkipOutputParametersAndLogForBinaryUpstream() throws Exception {
        int port = findFreePort();
        Component upstream = createBinaryServer(port, JPEG_BYTES, MediaType.IMAGE_JPEG);
        upstream.start();

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) ((org.restlet.ext.slf4j.Slf4jLogger) org.restlet.Context.getCurrentLogger()).getSlf4jLogger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        try {
            ToolHandler handler =
                    handlerFromYaml(binaryToolWithOutputParametersYaml(port));
            McpSchema.CallToolResult result =
                    handler.handleToolCall("get-photo", Map.of("id", "p-1"));

            // Mappings are bypassed: the result is the raw image, not a mapped text payload.
            assertEquals(Boolean.FALSE, result.isError());
            assertEquals(1, result.content().size());
            McpSchema.ImageContent image =
                    assertInstanceOf(McpSchema.ImageContent.class, result.content().get(0));
            assertEquals("image/jpeg", image.mimeType());
            assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), image.data());

            assertTrue(appender.list.stream().anyMatch(m ->
                    m != null && m.getFormattedMessage().contains("Skipping outputParameters mappings for tool 'get-photo'")),
                    "expected an INFO log that outputParameters were skipped, got: " + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            upstream.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private ToolHandler handlerFromYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);
        Capability capability = new Capability(spec);
        McpServerSpec mcpSpec = (McpServerSpec) capability.getServerAdapters().stream()
                .map(a -> a.getSpec())
                .filter(McpServerSpec.class::isInstance)
                .findFirst()
                .orElseThrow();
        return new ToolHandler(capability, mcpSpec.getTools(), mcpSpec.getNamespace());
    }

    private String binaryToolCapabilityYaml(int port, String outputMediaType) {
        return binaryToolCapabilityYaml(port, outputMediaType, null);
    }

    private String binaryToolCapabilityYaml(int port, String outputMediaType, String maxBinarySize) {
        String mediaLine = outputMediaType != null
                ? "\n            outputMediaType: \"" + outputMediaType + "\""
                : "";
        String capLine = maxBinarySize != null
                ? "\n            maxBinarySize: \"" + maxBinarySize + "\""
                : "";
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: photos
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      photoBytes:
                        path: /photos/binary
                        operations:
                          download:
                            method: GET
                            outputRawFormat: binary%s%s
                  exposes:
                  - type: mcp
                    transport: http
                    port: 0
                    namespace: photos
                    tools:
                      get-photo:
                        description: Get photo bytes
                        call: photos.download
                        inputParameters:
                          id:
                            type: string
                            description: Photo id
                            required: true
                        outputParameters:
                          caption:
                            type: string
                            mapping: "$.caption"
                """.formatted(schemaVersion, port, mediaLine, capLine);
    }

    /** Same as the basic binary tool capability but with outputParameters declared on the tool. */
    private String binaryToolWithOutputParametersYaml(int port) {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: photos
                    type: http
                    baseUri: http://localhost:%d
                    resources:
                      photoBytes:
                        path: /photos/binary
                        operations:
                          download:
                            method: GET
                            outputRawFormat: binary
                            outputMediaType: "image/jpeg"
                  exposes:
                  - type: mcp
                    transport: http
                    port: 0
                    namespace: photos
                    tools:
                      get-photo:
                        description: Get photo bytes
                        call: photos.download
                        inputParameters:
                          id:
                            type: string
                            description: Photo id
                            required: true
                        outputParameters:
                          caption:
                            type: string
                            mapping: "$.caption"
                """.formatted(schemaVersion, port);
    }

    private static Component createBinaryServer(int port, byte[] bytes, MediaType mediaType) {
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
