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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;

/**
 * Phase 5 of the binary-content blueprint (capability-binary-content.md §8.5 / §4.5): static file
 * resources must return {@code BlobResourceContents} for binary file types instead of reading the
 * bytes as UTF-8 text (which silently corrupts PNG/PDF/ZIP payloads).
 */
public class ResourceHandlerStaticBinaryTest {

    /** Minimal valid PNG signature + IHDR-ish bytes — content is opaque to the handler. */
    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            (byte) 0xFF, 0x00, (byte) 0xC3, 0x28, 0x01, 0x02, 0x03, 0x04
    };

    @TempDir
    Path tempDir;

    @Test
    public void readShouldReturnBase64BlobForStaticBinaryFile() throws Exception {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        Files.write(assets.resolve("logo.png"), PNG_BYTES);

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("assets", staticResource("assets", "files://brand", assets)), null);

        List<ResourceHandler.ResourceContent> content = handler.read("files://brand/logo.png");

        assertEquals(1, content.size());
        ResourceHandler.ResourceContent c = content.get(0);
        assertEquals("image/png", c.mimeType);
        assertNull(c.text, "binary content must not populate the text field");
        assertEquals(Base64.getEncoder().encodeToString(PNG_BYTES), c.blob);
    }

    @Test
    public void readShouldReturnBlobForOctetStreamWhenMimeUnknown() throws Exception {
        Path assets = tempDir.resolve("bin");
        Files.createDirectories(assets);
        byte[] raw = new byte[] { 0x00, 0x01, (byte) 0xFE, (byte) 0xFF, 0x10 };
        Files.write(assets.resolve("blob.dat"), raw);

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("bin", staticResource("bin", "files://bin", assets)), null);

        List<ResourceHandler.ResourceContent> content = handler.read("files://bin/blob.dat");

        assertEquals(1, content.size());
        ResourceHandler.ResourceContent c = content.get(0);
        assertNull(c.text);
        assertEquals(Base64.getEncoder().encodeToString(raw), c.blob);
    }

    @Test
    public void readShouldKeepTextPathForMarkdown() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("guide.md"), "# guide\n", StandardCharsets.UTF_8);

        ResourceHandler handler = new ResourceHandler(null,
                Map.of("docs", staticResource("docs", "data://docs", docs)), null);

        List<ResourceHandler.ResourceContent> content = handler.read("data://docs/guide.md");

        assertEquals(1, content.size());
        ResourceHandler.ResourceContent c = content.get(0);
        assertEquals("text/markdown", c.mimeType);
        assertEquals("# guide\n", c.text);
        assertNull(c.blob, "text content must not populate the blob field");
    }

    @Test
    public void readShouldHonourExplicitBinaryMimeTypeOverride() throws Exception {
        // The file extension is text-looking (.bin) but the spec pins image/jpeg, so the handler
        // must take the binary path based on the declared MIME type.
        Path assets = tempDir.resolve("img");
        Files.createDirectories(assets);
        byte[] raw = new byte[] { 0x01, 0x02, 0x03 };
        Files.write(assets.resolve("photo.bin"), raw);

        McpServerResourceSpec spec = staticResource("img", "files://img", assets);
        spec.setMimeType("image/jpeg");

        ResourceHandler handler = new ResourceHandler(null, Map.of("img", spec), null);

        List<ResourceHandler.ResourceContent> content = handler.read("files://img/photo.bin");

        assertEquals("image/jpeg", content.get(0).mimeType);
        assertEquals(Base64.getEncoder().encodeToString(raw), content.get(0).blob);
    }

    // ── isBinaryMime classifier ──────────────────────────────────────────────────────────────────

    @Test
    public void isBinaryMimeShouldTreatTextFamilyAsText() {
        assertFalse(ResourceHandler.isBinaryMime("text/plain"));
        assertFalse(ResourceHandler.isBinaryMime("text/markdown"));
        assertFalse(ResourceHandler.isBinaryMime("application/json"));
        assertFalse(ResourceHandler.isBinaryMime("application/yaml"));
        assertFalse(ResourceHandler.isBinaryMime("application/x-yaml"));
        assertFalse(ResourceHandler.isBinaryMime("application/xml"));
        assertFalse(ResourceHandler.isBinaryMime("application/javascript"));
        assertFalse(ResourceHandler.isBinaryMime("text/plain; charset=utf-8"));
        assertFalse(ResourceHandler.isBinaryMime("application/vnd.api+json"));
        assertFalse(ResourceHandler.isBinaryMime("application/problem+xml"));
    }

    @Test
    public void isBinaryMimeShouldTreatNonTextAsBinary() {
        assertTrue(ResourceHandler.isBinaryMime("image/png"));
        assertTrue(ResourceHandler.isBinaryMime("image/jpeg"));
        assertTrue(ResourceHandler.isBinaryMime("audio/mpeg"));
        assertTrue(ResourceHandler.isBinaryMime("application/pdf"));
        assertTrue(ResourceHandler.isBinaryMime("application/zip"));
        assertTrue(ResourceHandler.isBinaryMime("application/octet-stream"));
        assertTrue(ResourceHandler.isBinaryMime(null));
        assertTrue(ResourceHandler.isBinaryMime("   "));
    }

    private static McpServerResourceSpec staticResource(String name, String uri, Path dir) {
        McpServerResourceSpec spec = new McpServerResourceSpec();
        spec.setName(name);
        spec.setUri(uri);
        spec.setLocation(dir.toUri().toString());
        return spec;
    }
}
