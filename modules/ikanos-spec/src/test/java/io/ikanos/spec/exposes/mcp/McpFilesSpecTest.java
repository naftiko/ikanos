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
package io.ikanos.spec.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The {@code files} block of an MCP adapter and the {@code download} block of a tool (the
 * experimental MCP files extension): schema validation and the spec model.
 */
class McpFilesSpecTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = McpFilesSpecTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static String capability(String files, String download) {
        return """
                ikanos: "%s"
                info:
                  display: "Files"
                  description: "Files block test"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "exports"
                      baseUri: "https://api.exports.test"
                      resources:
                        exports:
                          path: "/exports"
                          operations:
                            export:
                              method: "POST"
                  exposes:
                    - type: "mcp"
                      namespace: "exports-mcp"
                      port: 3001
                %s
                      tools:
                        export-orders:
                          description: "Export orders."
                          call: "exports.export"
                %s
                """.formatted(IKANOS, files.indent(6).stripTrailing(),
                download.indent(10).stripTrailing());
    }

    private static List<Error> validate(String files, String download) throws Exception {
        return schema.validate(YAML.readTree(capability(files, download)));
    }

    @Test
    void schemaShouldAcceptEveryField() throws Exception {
        List<Error> errors = validate("""
                files:
                  downloads:
                    inlineThreshold: 512KiB
                    ttl: PT30S
                """, """
                download:
                  url: url
                  size: size
                  mimeType: content-type
                  name: file-name
                  expiresAt: expires-at
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptAnEmptyFilesBlock() throws Exception {
        List<Error> errors = validate("files: {}", "");
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    @DisplayName("The shipped example capability-mcp-files.yml validates")
    void schemaShouldAcceptTheShippedFilesCapability() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("schemas/examples/capability-mcp-files.yml")) {
            assertNotNull(in, "the shipped example must be on the classpath");
            List<Error> errors = schema.validate(YAML.readTree(in));
            assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
        }
    }

    @Test
    void schemaShouldRejectAThresholdWithoutUnit() throws Exception {
        assertFalse(validate("""
                files:
                  downloads:
                    inlineThreshold: "1024"
                """, "").isEmpty());
    }

    @Test
    void schemaShouldRejectATtlThatIsNotAnIsoDuration() throws Exception {
        assertFalse(validate("""
                files:
                  downloads:
                    ttl: 5m
                """, "").isEmpty());
    }

    @Test
    void schemaShouldAcceptAnInlineUploadLimit() throws Exception {
        List<Error> errors = validate("""
                files:
                  uploads:
                    maxInlineBytes: 2MiB
                """, "");
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectAnInlineUploadLimitWithoutUnit() throws Exception {
        assertFalse(validate("""
                files:
                  uploads:
                    maxInlineBytes: "1048576"
                """, "").isEmpty());
    }

    @Test
    void schemaShouldAcceptHostedUploads() throws Exception {
        List<Error> errors = validate("""
                files:
                  uploads:
                    hosted: true
                    quota: 500MiB
                    ttl: PT2M
                """, "");
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectAHostedQuotaWithoutUnit() throws Exception {
        assertFalse(validate("""
                files:
                  uploads:
                    hosted: true
                    quota: "500"
                """, "").isEmpty());
    }

    @Test
    void schemaShouldRejectAHostedTtlThatIsNotAnIsoDuration() throws Exception {
        assertFalse(validate("""
                files:
                  uploads:
                    hosted: true
                    ttl: 10m
                """, "").isEmpty());
    }

    @Test
    void schemaShouldRejectAHostedFlagThatIsNotABoolean() throws Exception {
        assertFalse(validate("""
                files:
                  uploads:
                    hosted: "yes"
                """, "").isEmpty());
    }

    @Test
    void specShouldReadHostedUploadsAndApplyDefaults() throws Exception {
        IkanosSpec declared = YAML.readValue(capability("""
                files:
                  uploads:
                    hosted: true
                    quota: 500MiB
                    ttl: PT2M
                """, ""), IkanosSpec.class);
        McpFileUploadsSpec uploads = ((McpServerSpec) declared.getCapability().getExposes()
                .get(0)).getFiles().uploadsOrDefault();
        assertTrue(uploads.isHosted());
        assertEquals("500MiB", uploads.quotaOrDefault());
        assertEquals("PT2M", uploads.ttlOrDefault());

        IkanosSpec defaults = YAML.readValue(capability("files: {}", ""), IkanosSpec.class);
        McpFileUploadsSpec none = ((McpServerSpec) defaults.getCapability().getExposes().get(0))
                .getFiles().uploadsOrDefault();
        assertFalse(none.isHosted());
        assertNull(none.getHosted());
        assertEquals("100MiB", none.quotaOrDefault());
        assertEquals("PT5M", none.ttlOrDefault());
    }

    @Test
    void specShouldWriteHostedUploadsBack() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("""
                files:
                  uploads:
                    hosted: true
                    quota: 64MiB
                """, ""), IkanosSpec.class);

        String written = YAML.writeValueAsString(spec);
        assertTrue(written.contains("hosted: true"), written);
        assertTrue(written.contains("64MiB"), written);
        assertFalse(written.contains("ttl"), written);
    }

    @Test
    void schemaShouldRejectADownloadWithoutSize() throws Exception {
        assertFalse(validate("files: {}", """
                download:
                  url: url
                """).isEmpty());
    }

    @Test
    void specShouldReadTheFilesBlockAndApplyDefaults() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("files: {}", """
                download:
                  url: url
                  size: size
                """), IkanosSpec.class);
        McpServerSpec server = (McpServerSpec) spec.getCapability().getExposes().get(0);

        assertNotNull(server.getFiles());
        assertNull(server.getFiles().getDownloads());
        assertEquals("1MiB", server.getFiles().downloadsOrDefault().inlineThresholdOrDefault());
        assertEquals("PT5M", server.getFiles().downloadsOrDefault().ttlOrDefault());
        assertEquals("1MiB", server.getFiles().uploadsOrDefault().maxInlineBytesOrDefault());
        assertEquals("url", server.getTools().get("export-orders").getDownload().getUrl());
    }

    @Test
    void specShouldLeaveFilesAbsentWhenNotDeclared() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("", ""), IkanosSpec.class);
        McpServerSpec server = (McpServerSpec) spec.getCapability().getExposes().get(0);

        assertNull(server.getFiles());
        assertNull(server.getTools().get("export-orders").getDownload());
    }

    @Test
    void specShouldWriteTheFilesBlockBack() throws Exception {
        IkanosSpec spec = YAML.readValue(capability("""
                files:
                  downloads:
                    ttl: PT1M
                """, ""), IkanosSpec.class);

        String written = YAML.writeValueAsString(spec);
        assertTrue(written.contains("ttl: \"PT1M\"") || written.contains("ttl: PT1M"), written);
        assertFalse(written.contains("inlineThreshold"), written);
    }
}
