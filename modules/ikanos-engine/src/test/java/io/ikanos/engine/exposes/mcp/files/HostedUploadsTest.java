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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.exposes.mcp.files.HostedUploads.Received;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class HostedUploadsTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final Instant STATE_EXPIRES = NOW.plusSeconds(600);
    private static final byte[] PDF = "%PDF-1.7 report".getBytes(StandardCharsets.US_ASCII);

    /** A clock the test moves forward. */
    private static final class MovingClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();

    private HostedUploads hosted(long quota) {
        return new HostedUploads(new MemoryUploadStore(quota), Duration.ofMinutes(5), clock,
                "docs");
    }

    private static FileInputs.PendingFile pending(String id, String mimeType, long size) {
        return new FileInputs.PendingFile("report", id, "report.pdf", mimeType, size);
    }

    private static InputParameterSpec input(String... accept) {
        InputParameterSpec input = new InputParameterSpec();
        input.setName("report");
        input.setType("file");
        input.getAccept().addAll(List.of(accept));
        return input;
    }

    private static Received put(HostedUploads hosted, String token, String principal,
            String type, long length, byte[] body) {
        return hosted.receive(token, principal, type, length, new ByteArrayInputStream(body));
    }

    @Test
    void issueShouldReturnAPathTargetWithIncludeCredentials() {
        HostedUploads.Issued issued = hosted(1000).issue(JSON,
                List.of(pending("f1", "application/pdf", PDF.length)), "alice", "s1",
                STATE_EXPIRES).get(0);

        ObjectNode target = issued.target();
        assertEquals("f1", target.path("fileId").asString());
        assertEquals("/files/" + issued.token(), target.path("path").asString());
        assertEquals(43, issued.token().length());
        assertFalse(target.has("url"));
        assertFalse(target.has("method"), "PUT is the default method");
        assertEquals("include", target.path("credentials").asString());
        assertEquals("application/pdf", target.path("headers").path("Content-Type").asString());
        assertEquals(NOW.plusSeconds(300).toString(), target.path("expiresAt").asString());
        assertEquals(issued.token(), McpFileDownloads.tokenOf("/mcp/files/" + issued.token()));
    }

    @Test
    void issueShouldNeverLetATargetOutliveTheRequestState() {
        HostedUploads hosted = new HostedUploads(new MemoryUploadStore(1000),
                Duration.ofHours(1), clock, "docs");

        ObjectNode target = hosted.issue(JSON, List.of(pending("f1", "application/pdf", 1)),
                "alice", "s1", STATE_EXPIRES).get(0).target();

        assertEquals(STATE_EXPIRES.toString(), target.path("expiresAt").asString());
    }

    @Test
    void issueShouldRefuseFilesOverTheQuota() {
        HostedUploads hosted = hosted(PDF.length);
        hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)), "alice", "s1",
                STATE_EXPIRES);

        FileInputs.InvalidFileException refused = assertThrows(
                FileInputs.InvalidFileException.class,
                () -> hosted.issue(JSON, List.of(pending("f2", "application/pdf", 1)), "alice",
                        "s2", STATE_EXPIRES));
        assertTrue(refused.getMessage().contains("do not fit"), refused.getMessage());
    }

    @Test
    void issueShouldRefuseATypeThatIsNotAMediaType() {
        assertThrows(FileInputs.InvalidFileException.class, () -> hosted(1000).issue(JSON,
                List.of(pending("f1", "application/pdf\r\nX-Evil: 1", 1)), "alice", "s1",
                STATE_EXPIRES));
        assertThrows(FileInputs.InvalidFileException.class, () -> hosted(1000).issue(JSON,
                List.of(pending("f1", "pdf", 1)), "alice", "s1", STATE_EXPIRES));
    }

    @Test
    void receiveShouldStoreTheExactBytesOnce() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();

        assertEquals(Received.STORED,
                put(hosted, token, "alice", "application/pdf", PDF.length, PDF));
        assertEquals(Received.ALREADY_WRITTEN,
                put(hosted, token, "alice", "application/pdf", PDF.length, PDF));
        FileInputs.Uploaded uploaded = hosted.confirm(input(), token, "s1", "alice", Map.of(),
                PDF.length);
        assertArrayEquals(PDF, uploaded.bytes());
        assertNull(uploaded.uploadId());
    }

    @Test
    void receiveShouldRefuseAnotherPrincipalWithoutUsingTheTarget() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();

        assertEquals(Received.NOT_FOUND,
                put(hosted, token, "mallory", "application/pdf", PDF.length, PDF));
        assertEquals(Received.STORED,
                put(hosted, token, "alice", "application/pdf", PDF.length, PDF));
    }

    @Test
    void receiveShouldCheckTypeAndLengthAndLeaveTheTargetOpenWhenRefused() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();

        assertEquals(Received.WRONG_TYPE,
                put(hosted, token, "alice", "text/html", PDF.length, PDF));
        assertEquals(Received.WRONG_TYPE, put(hosted, token, "alice", null, PDF.length, PDF));
        assertEquals(Received.LENGTH_REQUIRED,
                put(hosted, token, "alice", "application/pdf", -1, PDF));
        assertEquals(Received.TOO_LARGE,
                put(hosted, token, "alice", "application/pdf", PDF.length + 1, PDF));
        assertEquals(Received.TOO_SHORT,
                put(hosted, token, "alice", "application/pdf", PDF.length - 1, PDF));
        // A Content-Length that lies: the body is read up to the declared size, no further
        byte[] longer = (new String(PDF, StandardCharsets.US_ASCII) + "+more")
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals(Received.TOO_LARGE,
                put(hosted, token, "alice", "application/pdf", PDF.length, longer));
        assertEquals(Received.TOO_SHORT, put(hosted, token, "alice", "application/pdf",
                PDF.length, new byte[3]));

        assertEquals(Received.STORED,
                put(hosted, token, "alice", "APPLICATION/PDF", PDF.length, PDF));
    }

    @Test
    void receiveShouldRefuseAnExpiredTarget() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        clock.now = NOW.plusSeconds(300);

        assertEquals(Received.NOT_FOUND,
                put(hosted, token, "alice", "application/pdf", PDF.length, PDF));
    }

    @Test
    void confirmShouldRefuseAFileThatWasNotUploaded() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();

        String message = assertThrows(FileInputs.InvalidFileException.class,
                () -> hosted.confirm(input(), token, "s1", "alice", Map.of(), PDF.length))
                .getMessage();
        assertTrue(message.contains("was not uploaded"), message);
    }

    @Test
    void confirmShouldRefuseBytesOfAnotherState() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        put(hosted, token, "alice", "application/pdf", PDF.length, PDF);

        assertThrows(FileInputs.InvalidFileException.class,
                () -> hosted.confirm(input(), token, "s2", "alice", Map.of(), PDF.length));
    }

    @Test
    void confirmShouldInspectTheBytesWhenTheInputRestrictsTheType() {
        byte[] html = "<html>not a pdf</html>".getBytes(StandardCharsets.US_ASCII);
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", html.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        put(hosted, token, "alice", "application/pdf", html.length, html);

        String message = assertThrows(FileInputs.InvalidFileException.class,
                () -> hosted.confirm(input("application/pdf"), token, "s1", "alice", Map.of(),
                        html.length)).getMessage();
        assertTrue(message.contains("does not look like"), message);
        // Without accept, the tool takes any bytes
        assertArrayEquals(html,
                hosted.confirm(input(), token, "s1", "alice", Map.of(), html.length).bytes());
    }

    @Test
    void confirmShouldCheckTheDigestWhenGiven() {
        HostedUploads hosted = hosted(1000);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        put(hosted, token, "alice", "application/pdf", PDF.length, PDF);

        Map<String, Object> good = Map.of("digest",
                Map.of("algorithm", "sha-256", "value", FileInputs.digestOf(PDF)));
        Map<String, Object> bad = Map.of("digest", Map.of("algorithm", "sha-256", "value",
                FileInputs.digestOf("other".getBytes(StandardCharsets.US_ASCII))));

        assertThrows(FileInputs.InvalidFileException.class, () -> hosted.confirm(
                input("application/pdf"), token, "s1", "alice", bad, PDF.length));
        assertArrayEquals(PDF, hosted.confirm(input("application/pdf"), token, "s1", "alice",
                good, PDF.length).bytes());
    }

    @Test
    void releaseShouldDeleteTheBytesAndFreeTheQuota() {
        HostedUploads hosted = hosted(PDF.length);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        put(hosted, token, "alice", "application/pdf", PDF.length, PDF);

        hosted.release(List.of(token));

        assertThrows(FileInputs.InvalidFileException.class,
                () -> hosted.confirm(input(), token, "s1", "alice", Map.of(), PDF.length));
        hosted.issue(JSON, List.of(pending("f2", "application/pdf", PDF.length)), "alice", "s2",
                STATE_EXPIRES);
    }

    @Test
    void purgeShouldDropUploadsOnceTheirStateExpired() {
        HostedUploads hosted = hosted(PDF.length);
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        put(hosted, token, "alice", "application/pdf", PDF.length, PDF);
        clock.now = STATE_EXPIRES;

        assertEquals(1, hosted.purge());
    }

    private static McpServerSpec server(String uploads, boolean stdio) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  consumes:
                  - namespace: records
                    type: http
                    baseUri: http://127.0.0.1:1
                    resources:
                      r:
                        path: /r
                        operations:
                          op:
                            method: GET
                  exposes:
                  - type: mcp
                    %s
                    namespace: docs-mcp
                    files:
                      uploads:
                %s
                    tools:
                      t:
                        description: t
                        call: records.op
                """.formatted(VersionHelper.getSchemaVersion(),
                stdio ? "transport: stdio" : "port: 3001", uploads.indent(8).stripTrailing()),
                IkanosSpec.class);
        return (McpServerSpec) spec.getCapability().getExposes().get(0);
    }

    @Test
    void ofShouldReadTheSettingsAndCheckThem() throws Exception {
        assertNull(HostedUploads.of(server("maxInlineBytes: 1KiB", false), clock));
        assertTrue(HostedUploads.of(server("hosted: true", false), clock) != null);
        assertThrows(IllegalArgumentException.class,
                () -> HostedUploads.of(server("hosted: true", true), clock));
        assertThrows(IllegalArgumentException.class,
                () -> HostedUploads.of(server("hosted: true\nttl: 5m", false), clock));
        assertThrows(IllegalArgumentException.class,
                () -> HostedUploads.of(server("hosted: true\nttl: PT0S", false), clock));
    }

    @Test
    void ofShouldRefuseAnAuthenticationTheClientDoesNotResend() throws Exception {
        McpServerSpec header = server("hosted: true", false);
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setPlacement("header");
        header.setAuthentication(apiKey);
        McpServerSpec digest = server("hosted: true", false);
        digest.setAuthentication(new DigestAuthenticationSpec());
        McpServerSpec query = server("hosted: true", false);
        ApiKeyAuthenticationSpec queryKey = new ApiKeyAuthenticationSpec();
        queryKey.setPlacement("query");
        query.setAuthentication(queryKey);
        McpServerSpec bearer = server("hosted: true", false);
        bearer.setAuthentication(new BearerAuthenticationSpec());

        assertThrows(IllegalArgumentException.class, () -> HostedUploads.of(header, clock));
        assertThrows(IllegalArgumentException.class, () -> HostedUploads.of(digest, clock));
        assertTrue(HostedUploads.of(query, clock) != null);
        assertTrue(HostedUploads.of(bearer, clock) != null);
    }

    @Test
    void stopShouldDropTheSlotsAndGiveTheBudgetBack() {
        FileMemoryBudget budget = new FileMemoryBudget(1000, 1000);
        HostedUploads hosted = new HostedUploads(new MemoryUploadStore(1000, 10, budget, null),
                Duration.ofMinutes(5), clock, "docs");
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        hosted.start();

        hosted.stop();

        assertEquals(0, budget.used());
        assertEquals(Received.NOT_FOUND,
                put(hosted, token, "alice", "application/pdf", PDF.length, PDF));
    }

    @Test
    void aWriteThatEndsAfterItsSlotWasDroppedShouldNotBeAcknowledged() {
        MemoryUploadStore store = new MemoryUploadStore(1000);
        HostedUploads hosted = new HostedUploads(store, Duration.ofMinutes(5), clock, "docs");
        String token = hosted.issue(JSON, List.of(pending("f1", "application/pdf", PDF.length)),
                "alice", "s1", STATE_EXPIRES).get(0).token();
        // The body arrives slowly: the slot is purged while it is being read
        java.io.InputStream slow = new ByteArrayInputStream(PDF) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                store.clear();
                return super.read(b, off, len);
            }

            @Override
            public int readNBytes(byte[] b, int off, int len) {
                store.clear();
                return super.readNBytes(b, off, len);
            }
        };

        assertEquals(Received.NOT_FOUND,
                hosted.receive(token, "alice", "application/pdf", PDF.length, slow));
    }
}
