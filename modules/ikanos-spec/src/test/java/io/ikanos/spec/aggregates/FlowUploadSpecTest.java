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
package io.ikanos.spec.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
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
 * The {@code upload} block of an aggregate flow (experimental, MCP files extension through
 * presigned URLs) and the {@code HEAD} method of a consumed operation: schema validation and the
 * spec model.
 */
class FlowUploadSpecTest {

    private static final YAMLMapper YAML = IkanosMappers.yaml();
    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = FlowUploadSpecTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(JSON.readTree(in));
        }
    }

    private static final String FULL_UPLOAD = """
            presign:
              call: "storage.create-upload-url"
              with:
                key: "scans/{{scan.uploadId}}"
              url: "$.uploadUrl"
              headers: "$.headers"
              expiresAt: "$.expiresAt"
            verify:
              call: "storage.head-object"
              with:
                key: "scans/{{scan.uploadId}}"
              size: "$.size"
              mimeType: "$.contentType"
            """;

    private static String capability(String upload) {
        return """
                ikanos: "%s"
                info:
                  display: "Uploads"
                  description: "Presigned upload test"
                capability:
                  aggregates:
                    - display: "Documents"
                      namespace: "documents"
                      flows:
                        import-scan:
                          description: "Import a scan."
                          semantics:
                            idempotent: false
                          inputParameters:
                            scan:
                              type: "file"
                              description: "The scan."
                          upload:
                %s
                          call: "records.import"
                          with:
                            key: "scans/{{scan.uploadId}}"
                  consumes:
                    - type: "http"
                      namespace: "storage"
                      baseUri: "https://storage.test"
                      resources:
                        objects:
                          path: "/objects/{{key}}"
                          operations:
                            create-upload-url:
                              method: "POST"
                            head-object:
                              method: "HEAD"
                    - type: "http"
                      namespace: "records"
                      baseUri: "https://records.test"
                      resources:
                        imports:
                          path: "/imports"
                          operations:
                            import:
                              method: "POST"
                  exposes:
                    - type: "mcp"
                      namespace: "documents-mcp"
                      port: 3001
                      files: {}
                      tools:
                        import-scan:
                          description: "Import a scan."
                          ref: "documents.import-scan"
                """.formatted(IKANOS, upload.indent(14).stripTrailing());
    }

    private static List<Error> validate(String upload) throws Exception {
        return schema.validate(YAML.readTree(capability(upload)));
    }

    @Test
    void schemaShouldAcceptAnUploadWithEveryField() throws Exception {
        List<Error> errors = validate(FULL_UPLOAD);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldAcceptAnUploadWithOnlyItsCalls() throws Exception {
        List<Error> errors = validate("""
                presign:
                  call: "storage.create-upload-url"
                verify:
                  call: "storage.head-object"
                """);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void schemaShouldRejectAnUploadWithoutVerify() throws Exception {
        assertFalse(validate("""
                presign:
                  call: "storage.create-upload-url"
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectAPresignWithoutCall() throws Exception {
        assertFalse(validate("""
                presign:
                  url: "$.url"
                verify:
                  call: "storage.head-object"
                """).isEmpty());
    }

    @Test
    void schemaShouldRejectAnUnknownUploadField() throws Exception {
        assertFalse(validate("""
                presign:
                  call: "storage.create-upload-url"
                  method: "POST"
                verify:
                  call: "storage.head-object"
                """).isEmpty());
    }

    @Test
    void specShouldReadTheUploadBlock() throws Exception {
        FlowUploadSpec upload = flow(FULL_UPLOAD).getUpload();

        assertEquals("storage.create-upload-url", upload.getPresign().getCall());
        assertEquals("scans/{{scan.uploadId}}", upload.getPresign().getWith().get("key"));
        assertEquals("$.uploadUrl", upload.getPresign().urlOrDefault());
        assertEquals("$.headers", upload.getPresign().getHeaders());
        assertEquals("$.expiresAt", upload.getPresign().getExpiresAt());
        assertEquals("storage.head-object", upload.getVerify().getCall());
        assertEquals("$.size", upload.getVerify().getSize());
        assertEquals("$.contentType", upload.getVerify().getMimeType());
    }

    @Test
    void presignUrlShouldDefaultToTheUrlMember() throws Exception {
        UploadPresignSpec presign = flow("""
                presign:
                  call: "storage.create-upload-url"
                verify:
                  call: "storage.head-object"
                """).getUpload().getPresign();

        assertNull(presign.getUrl());
        assertEquals("$.url", presign.urlOrDefault());
    }

    @Test
    void specShouldWriteTheUploadBlockBack() throws Exception {
        IkanosSpec spec = YAML.readValue(capability(FULL_UPLOAD), IkanosSpec.class);

        String written = YAML.writeValueAsString(spec);
        IkanosSpec read = YAML.readValue(written, IkanosSpec.class);

        FlowUploadSpec upload = read.getCapability().getAggregates().get("documents").getFlows()
                .get("import-scan").getUpload();
        assertEquals("$.uploadUrl", upload.getPresign().getUrl());
        assertEquals("storage.head-object", upload.getVerify().getCall());
    }

    @Test
    void flowWithoutUploadShouldHaveNone() throws Exception {
        AggregateFlowSpec flow = new AggregateFlowSpec();

        assertNull(flow.getUpload());
        assertFalse(YAML.writeValueAsString(flow).contains("upload"));
    }

    private static AggregateFlowSpec flow(String upload) throws Exception {
        IkanosSpec spec = YAML.readValue(capability(upload), IkanosSpec.class);
        return spec.getCapability().getAggregates().get("documents").getFlows()
                .get("import-scan");
    }
}
