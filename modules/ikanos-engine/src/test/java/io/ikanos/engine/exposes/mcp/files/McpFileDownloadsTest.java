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
package io.ikanos.engine.exposes.mcp.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpFileDownloadsSpec;
import io.ikanos.spec.exposes.mcp.McpFilesSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.exposes.mcp.McpToolDownloadSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpFileDownloadsTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ALICE = "alice";

    private static McpFileDownloads downloads(boolean hosted) {
        return new McpFileDownloads(10, Duration.ofMinutes(5), hosted, new MemoryDownloadStore(256, 1 << 20, 1 << 20),
                CLOCK);
    }

    private static JsonNode json(McpSchema.CallToolResult result) {
        return JSON.valueToTree(result);
    }

    @Test
    void hostedShouldListAPathTargetWhenTheResultIsAboveTheThreshold() {
        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(true).hosted("get-photo", new byte[11], "image/png", ALICE));

        JsonNode target = McpFiles.downloadsOf(json(result)).get(0);
        assertEquals("get-photo.png", target.path("name").asString());
        assertEquals("image/png", target.path("mimeType").asString());
        assertEquals(11, target.path("size").asInt());
        assertEquals("include", target.path("credentials").asString());
        assertEquals("2026-11-26T10:05:00Z", target.path("expiresAt").asString());
        assertEquals("sha-256", target.path("digest").path("algorithm").asString());
        assertNotNull(McpFileDownloads.tokenOf(target.path("path").asString()), target.toString());
        assertFalse(target.has("url"), "Exactly one of url and path: " + target);
    }

    @Test
    void hostedShouldDescribeTheFileInContentWithoutItsBytes() {
        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(true).hosted("get-photo", new byte[11], "image/png", ALICE));

        JsonNode content = json(result).path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).path("type").asString());
        assertTrue(content.get(0).path("text").asString().contains("11 bytes"), content.toString());
        assertFalse(json(result).has("structuredContent"));
    }

    @Test
    void hostedShouldKeepTheResultInlineWhenItIsNotAboveTheThreshold() {
        assertNull(McpFiles.withDownloads(true,
                () -> downloads(true).hosted("get-photo", new byte[10], "image/png", ALICE)));
    }

    @Test
    void hostedShouldKeepTheResultInlineWhenTheRequestDidNotDeclareDownload() {
        assertNull(downloads(true).hosted("get-photo", new byte[11], "image/png", ALICE));
    }

    @Test
    void hostedShouldKeepTheResultInlineOnStdio() {
        assertNull(McpFiles.withDownloads(true,
                () -> downloads(false).hosted("get-photo", new byte[11], "image/png", ALICE)));
    }

    @Test
    void hostedShouldKeepTheResultInlineWhenTheStoreIsFull() {
        McpFileDownloads full = new McpFileDownloads(10, Duration.ofMinutes(5), true,
                new MemoryDownloadStore(1, 1024, 1024), CLOCK);
        McpFiles.withDownloads(true, () -> full.hosted("a", new byte[11], "image/png", ALICE));

        assertNull(McpFiles.withDownloads(true,
                () -> full.hosted("b", new byte[11], "image/png", ALICE)));
    }

    @Test
    void takeShouldServeAHostedFileOnceToItsPrincipal() {
        McpFileDownloads downloads = downloads(true);
        byte[] bytes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads.hosted("get-photo", bytes, "image/png", ALICE));
        String token = McpFileDownloads.tokenOf(
                McpFiles.downloadsOf(json(result)).get(0).path("path").asString());

        assertTrue(downloads.take(token, "mallory").isEmpty(), "Another principal gets nothing");
        assertArrayEquals(bytes, downloads.take(token, ALICE).orElseThrow().bytes());
        assertTrue(downloads.take(token, ALICE).isEmpty(), "A link serves its file once");
    }

    private static McpServerToolSpec passThroughTool() {
        McpToolDownloadSpec download = new McpToolDownloadSpec();
        download.setUrl("downloadUrl");
        download.setSize("sizeBytes");
        download.setMimeType("contentType");
        download.setName("fileName");
        download.setExpiresAt("expiresAt");
        McpServerToolSpec tool = new McpServerToolSpec("export-orders", null, "Export orders.");
        tool.setDownload(download);
        return tool;
    }

    @Test
    void passThroughShouldListTheUrlOfTheOutputWithCredentialsOmit() {
        String mapped = """
                {"downloadUrl": "https://storage.example.com/q3.csv?sig=abc", "sizeBytes": 2048,
                 "contentType": "text/csv", "fileName": "q3.csv",
                 "expiresAt": "2026-11-26T11:00:00Z"}""";

        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(true).passThrough(passThroughTool(), mapped));

        JsonNode target = McpFiles.downloadsOf(json(result)).get(0);
        assertEquals("https://storage.example.com/q3.csv?sig=abc", target.path("url").asString());
        assertEquals("omit", target.path("credentials").asString());
        assertEquals(2048, target.path("size").asInt());
        assertEquals("text/csv", target.path("mimeType").asString());
        assertEquals("q3.csv", target.path("name").asString());
        assertEquals("2026-11-26T11:00:00Z", target.path("expiresAt").asString());
        assertFalse(json(result).toString().contains("sig=abc\"}]"),
                "The URL is not in content: " + json(result).path("content"));
        assertFalse(json(result).path("content").toString().contains("storage.example.com"));
    }

    @Test
    void passThroughShouldWorkOnStdio() {
        String mapped = "{\"downloadUrl\": \"https://s.example.com/f\", \"sizeBytes\": \"7\"}";

        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(false).passThrough(passThroughTool(), mapped));

        JsonNode target = McpFiles.downloadsOf(json(result)).get(0);
        assertEquals(7, target.path("size").asInt());
        assertEquals("application/octet-stream", target.path("mimeType").asString());
    }

    @Test
    void passThroughShouldNormalizeTheExpiryToUtc() {
        String mapped = "{\"downloadUrl\": \"https://s.example.com/f\", \"sizeBytes\": 7,"
                + " \"expiresAt\": \"2026-11-26T12:00:00+02:00\"}";

        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(true).passThrough(passThroughTool(), mapped));

        assertEquals("2026-11-26T10:00:00Z",
                McpFiles.downloadsOf(json(result)).get(0).path("expiresAt").asString());
    }

    @Test
    void passThroughShouldLeaveOutAnExpiryThatIsNotATimestamp() {
        String mapped = "{\"downloadUrl\": \"https://s.example.com/f\", \"sizeBytes\": 7,"
                + " \"expiresAt\": \"3600\"}";

        McpSchema.CallToolResult result = McpFiles.withDownloads(true,
                () -> downloads(true).passThrough(passThroughTool(), mapped));

        JsonNode target = McpFiles.downloadsOf(json(result)).get(0);
        assertEquals("https://s.example.com/f", target.path("url").asString());
        assertFalse(target.has("expiresAt"), target.toString());
    }

    @Test
    void passThroughShouldKeepTheOutputWhenTheRequestDidNotDeclareDownload() {
        String mapped = "{\"downloadUrl\": \"https://s.example.com/f\", \"sizeBytes\": 7}";

        assertNull(downloads(true).passThrough(passThroughTool(), mapped));
    }

    @Test
    void passThroughShouldKeepTheOutputWhenTheUrlIsNotHttps() {
        String mapped = "{\"downloadUrl\": \"http://s.example.com/f\", \"sizeBytes\": 7}";

        assertNull(McpFiles.withDownloads(true,
                () -> downloads(true).passThrough(passThroughTool(), mapped)));
    }

    @Test
    void passThroughShouldKeepTheOutputWhenTheSizeIsMissing() {
        String mapped = "{\"downloadUrl\": \"https://s.example.com/f\"}";

        assertNull(McpFiles.withDownloads(true,
                () -> downloads(true).passThrough(passThroughTool(), mapped)));
    }

    @Test
    void sizeOfShouldRefuseANegativeOrFractionalSize() {
        assertNull(McpFileDownloads.sizeOf(JSON.readTree("-1")));
        assertNull(McpFileDownloads.sizeOf(JSON.readTree("1.5")));
        assertEquals(42L, McpFileDownloads.sizeOf(JSON.readTree("\"42\"")));
    }

    @Test
    void isHttpsShouldRefuseAUrlWithUserInfo() {
        assertFalse(McpFileDownloads.isHttps("https://user:pw@s.example.com/f"));
        assertTrue(McpFileDownloads.isHttps("https://s.example.com/f"));
    }

    @Test
    void tokenOfShouldFindTheTokenUnderAnyMcpAddress() {
        String token = "a".repeat(43);

        assertEquals(token, McpFileDownloads.tokenOf("/files/" + token));
        assertEquals(token, McpFileDownloads.tokenOf("/mcp/files/" + token));
        assertNull(McpFileDownloads.tokenOf("/files/short"));
        assertNull(McpFileDownloads.tokenOf("/"));
    }

    private static McpServerSpec server(McpFilesSpec files) {
        McpServerSpec spec = new McpServerSpec(null, 0, "files-mcp", null);
        spec.setFiles(files);
        return spec;
    }

    @Test
    void ofShouldReturnNullWithoutFiles() {
        assertNull(McpFileDownloads.of(server(null), CLOCK));
    }

    @Test
    void ofShouldApplyTheDefaultThreshold() {
        assertEquals(1024 * 1024,
                McpFileDownloads.of(server(new McpFilesSpec()), CLOCK).inlineThreshold());
    }

    @Test
    void ofShouldRefuseATtlThatIsNotAnIsoDuration() {
        McpFileDownloadsSpec settings = new McpFileDownloadsSpec();
        settings.setTtl("5m");
        McpFilesSpec files = new McpFilesSpec();
        files.setDownloads(settings);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpFileDownloads.of(server(files), CLOCK));
        assertTrue(e.getMessage().contains("ISO 8601"), e.getMessage());
    }

    @Test
    void ofShouldRefuseAToolDownloadWithoutFiles() {
        McpServerSpec spec = server(null);
        spec.getTools().put("export-orders", passThroughTool());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpFileDownloads.of(spec, CLOCK));
        assertTrue(e.getMessage().contains("export-orders"), e.getMessage());
    }

    @Test
    void resendsCredentialShouldAcceptWhatAClientResendsInAuthorizationOrTheQuery() {
        assertTrue(McpFileDownloads.resendsCredential(null));
        assertTrue(McpFileDownloads.resendsCredential(new BearerAuthenticationSpec()));
        assertTrue(McpFileDownloads.resendsCredential(
                new ApiKeyAuthenticationSpec("api_key", "query", "secret")));
    }

    @Test
    void resendsCredentialShouldRefuseAHeaderApiKeyOrDigest() {
        assertFalse(McpFileDownloads.resendsCredential(
                new ApiKeyAuthenticationSpec("X-API-Key", "header", "secret")));
        assertFalse(McpFileDownloads.resendsCredential(new DigestAuthenticationSpec()));
    }

    @Test
    void ofShouldNotHostDownloadsBehindAHeaderApiKey() {
        McpServerSpec spec = server(new McpFilesSpec());
        spec.setAuthentication(new ApiKeyAuthenticationSpec("X-API-Key", "header", "secret"));

        assertFalse(McpFileDownloads.of(spec, CLOCK).hostsDownloads());
    }

    @Test
    void resultMetaShouldNestTheTargetsUnderTheExtensionId() {
        Map<String, Object> meta = McpFiles.resultMeta(java.util.List.of(Map.of("size", 1)));

        assertTrue(meta.containsKey("io.modelcontextprotocol/files"), meta.toString());
    }

    @Test
    void dropLinksOfShouldDropOnlyTheLinksOfThatAdapter() {
        McpFileDownloads downloads = downloads(true);
        String docs = "[\"docs-mcp\",\"sub\",\"alice\"]";
        String docsV2 = "[\"docs-mcp-v2\",\"sub\",\"alice\"]";
        String first = McpFileDownloads.tokenOf(McpFiles.downloadsOf(json(McpFiles.withDownloads(true,
                () -> downloads.hosted("t", new byte[64], "application/octet-stream", docs)))).get(0).path("path").asString());
        String second = McpFileDownloads.tokenOf(McpFiles.downloadsOf(json(McpFiles.withDownloads(true,
                () -> downloads.hosted("t", new byte[64], "application/octet-stream", docsV2)))).get(0).path("path")
                .asString());

        downloads.dropLinksOf("docs-mcp");

        assertTrue(downloads.take(first, docs).isEmpty());
        assertTrue(downloads.take(second, docsV2).isPresent());
    }
}
