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
package io.ikanos.engine.aggregates;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.util.FileInputs;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.FlowUploadSpec;
import io.ikanos.spec.aggregates.SemanticsSpec;
import io.ikanos.spec.aggregates.UploadPresignSpec;
import io.ikanos.spec.aggregates.UploadVerifySpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FlowUploadTest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private static UploadPresignSpec presign() {
        UploadPresignSpec presign = new UploadPresignSpec();
        presign.setCall("storage.create-upload-url");
        presign.setUrl("$.uploadUrl");
        presign.setHeaders("$.headers");
        presign.setExpiresAt("$.expiresAt");
        return presign;
    }

    private static JsonNode answer(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static InputParameterSpec fileInput(String... accept) {
        InputParameterSpec input = new InputParameterSpec();
        input.setName("scan");
        input.setType("file");
        input.getAccept().addAll(List.of(accept));
        return input;
    }

    @Test
    void targetOfShouldReadTheUrlHeadersAndExpiry() throws Exception {
        FlowUpload.Target target = FlowUpload.targetOf(answer("""
                {"uploadUrl": "https://bucket.example.org/o/1?sig=x",
                 "headers": {"Content-Type": "application/pdf"},
                 "expiresAt": "2026-11-27T11:00:00+01:00"}
                """), presign(), "scan");

        assertEquals("https://bucket.example.org/o/1?sig=x", target.url());
        assertEquals(Map.of("Content-Type", "application/pdf"), target.headers());
        assertEquals(Instant.parse("2026-11-27T10:00:00Z"), target.expiresAt());
    }

    @Test
    void targetOfShouldDefaultToTheUrlMember() throws Exception {
        UploadPresignSpec presign = new UploadPresignSpec();
        presign.setCall("storage.create-upload-url");

        FlowUpload.Target target = FlowUpload.targetOf(
                answer("{\"url\": \"https://bucket.example.org/o/1\"}"), presign, "scan");

        assertEquals("https://bucket.example.org/o/1", target.url());
        assertTrue(target.headers().isEmpty());
        assertNull(target.expiresAt());
    }

    @Test
    void targetOfShouldRefuseAPlainHttpUrlOnAnotherHost() {
        assertThrows(FileInputs.InvalidFileException.class, () -> FlowUpload.targetOf(
                answer("{\"uploadUrl\": \"http://bucket.example.org/o/1\"}"), presign(), "scan"));
    }

    @Test
    void targetOfShouldRefuseAMissingUrl() {
        assertThrows(FileInputs.InvalidFileException.class,
                () -> FlowUpload.targetOf(answer("{}"), presign(), "scan"));
    }

    @Test
    void targetOfShouldRefuseAnAuthorizationHeader() {
        assertThrows(FileInputs.InvalidFileException.class, () -> FlowUpload.targetOf(answer("""
                {"uploadUrl": "https://bucket.example.org/o/1",
                 "headers": {"Authorization": "Bearer secret"}}
                """), presign(), "scan"));
    }

    @Test
    void targetOfShouldLeaveOutAnExpiryThatIsNotATimestamp() throws Exception {
        FlowUpload.Target target = FlowUpload.targetOf(answer("""
                {"uploadUrl": "https://bucket.example.org/o/1", "expiresAt": "soon"}
                """), presign(), "scan");

        assertNull(target.expiresAt());
    }

    @Test
    void isUploadUrlShouldAcceptHttpsAndLoopbackHttpOnly() {
        assertTrue(FlowUpload.isUploadUrl("https://bucket.example.org/o/1"));
        assertTrue(FlowUpload.isUploadUrl("http://127.0.0.1:9000/o/1"));
        assertTrue(FlowUpload.isUploadUrl("http://localhost:9000/o/1"));
        assertTrue(FlowUpload.isUploadUrl("http://[::1]:9000/o/1"));
        assertFalse(FlowUpload.isUploadUrl("http://10.0.0.1/o/1"));
        assertFalse(FlowUpload.isUploadUrl("https://user:pw@bucket.example.org/o/1"));
        assertFalse(FlowUpload.isUploadUrl("/relative/path"));
        assertFalse(FlowUpload.isUploadUrl("ftp://bucket.example.org/o/1"));
    }

    @Test
    void isSendableHeaderShouldRefuseCredentialsAndOverrides() {
        assertTrue(FlowUpload.isSendableHeader("Content-Type", "application/pdf"));
        assertTrue(FlowUpload.isSendableHeader("x-amz-checksum-sha256", "abc="));
        assertFalse(FlowUpload.isSendableHeader("Cookie", "a=b"));
        assertFalse(FlowUpload.isSendableHeader("Host", "evil.example"));
        assertFalse(FlowUpload.isSendableHeader("X-Forwarded-For", "1.2.3.4"));
        assertFalse(FlowUpload.isSendableHeader("X-HTTP-Method-Override", "DELETE"));
        assertFalse(FlowUpload.isSendableHeader("Bad Name", "x"));
        assertFalse(FlowUpload.isSendableHeader("X-Note", "line\r\nInjected: 1"));
    }

    @Test
    void checkVerifiedShouldAcceptTheDeclaredSizeAndAnAcceptedType() {
        assertDoesNotThrow(() -> FlowUpload.checkVerified(fileInput("application/pdf"), 42, "42",
                "application/pdf; charset=binary"));
    }

    @Test
    void checkVerifiedShouldRefuseAnotherSize() {
        assertThrows(FileInputs.InvalidFileException.class,
                () -> FlowUpload.checkVerified(fileInput(), 42, "41", null));
    }

    @Test
    void checkVerifiedShouldRefuseAnUnknownSize() {
        assertThrows(FileInputs.InvalidFileException.class,
                () -> FlowUpload.checkVerified(fileInput(), 42, null, null));
    }

    @Test
    void checkVerifiedShouldRefuseATypeTheInputDoesNotAccept() {
        assertThrows(FileInputs.InvalidFileException.class, () -> FlowUpload.checkVerified(
                fileInput("application/pdf"), 42, "42", "text/html"));
    }

    @Test
    void checkVerifiedShouldRefuseAMissingTypeWhenTheInputRestrictsIt() {
        assertThrows(FileInputs.InvalidFileException.class,
                () -> FlowUpload.checkVerified(fileInput("application/pdf"), 42, "42", null));
    }

    @Test
    void checkVerifiedShouldIgnoreTheTypeWhenTheInputTakesAny() {
        assertDoesNotThrow(() -> FlowUpload.checkVerified(fileInput(), 42, "42", null));
    }

    @Test
    void singleUseShouldHoldUnlessTheFlowIsIdempotentOrSafe() {
        assertTrue(upload(null).singleUse());
        assertTrue(upload(new SemanticsSpec(null, false, null)).singleUse());
        assertFalse(upload(new SemanticsSpec(null, true, null)).singleUse());
        assertFalse(upload(new SemanticsSpec(true, null, null)).singleUse());
    }

    @Test
    void checkUploadShouldRefuseAFlowThatTakesNoFile() {
        AggregateFlowSpec spec = new AggregateFlowSpec();
        spec.setName("import");
        spec.setUpload(fullUpload());

        assertThrows(IllegalArgumentException.class,
                () -> AggregateFlow.checkUpload(spec, "documents"));
    }

    @Test
    void checkUploadShouldRefuseAnUploadWithoutVerify() {
        AggregateFlowSpec spec = new AggregateFlowSpec();
        spec.setName("import");
        spec.setInputParameters(Map.of("scan", fileInput()));
        spec.setUpload(new FlowUploadSpec(presign(), null));

        assertThrows(IllegalArgumentException.class,
                () -> AggregateFlow.checkUpload(spec, "documents"));
    }

    private static FlowUploadSpec fullUpload() {
        UploadVerifySpec verify = new UploadVerifySpec();
        verify.setCall("storage.head-object");
        return new FlowUploadSpec(presign(), verify);
    }

    private static FlowUpload upload(SemanticsSpec semantics) {
        return new FlowUpload(fullUpload(), null, "documents", "documents.import", semantics);
    }
}
