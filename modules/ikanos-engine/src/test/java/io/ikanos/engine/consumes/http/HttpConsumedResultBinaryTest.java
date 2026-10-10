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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.representation.ByteArrayRepresentation;
import org.restlet.representation.StringRepresentation;

import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.util.BinarySize;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;

/**
 * Binary-content core on {@link HttpConsumedResult}: byte-faithful buffering, the
 * {@code maxBinarySize} cap, and the §4.3.1 media-type precedence. Moved unchanged in substance
 * from the former {@code HandlingContextBinaryTest} (see
 * {@code design-docs/consumed-invocation-carrier.md} §4.4).
 */
public class HttpConsumedResultBinaryTest {

    private static final byte[] PNG_MAGIC =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static HttpClientOperationSpec op(String outputRawFormat, String outputMediaType) {
        HttpClientOperationSpec op = new HttpClientOperationSpec();
        op.setOutputRawFormat(outputRawFormat);
        op.setOutputMediaType(outputMediaType);
        return op;
    }

    private static HttpConsumedResult resultWith(HttpClientOperationSpec op, Response response) {
        return new HttpConsumedResult(op, response);
    }

    private static HttpConsumedResult resultWith(byte[] bytes, MediaType upstreamType,
            String outputRawFormat, String outputMediaType) {
        Response response = new Response(new Request());
        response.setEntity(new ByteArrayRepresentation(bytes, upstreamType));
        return resultWith(op(outputRawFormat, outputMediaType), response);
    }

    @Test
    public void bytesShouldReturnExactBytesWhenUnderCap() throws Exception {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertArrayEquals(PNG_MAGIC, result.bytes(1024));
    }

    @Test
    public void bytesShouldNotCorruptHighBitBytes() throws Exception {
        byte[] payload = new byte[256];
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) i;
        }
        HttpConsumedResult result =
                resultWith(payload, MediaType.APPLICATION_OCTET_STREAM, "binary", null);

        assertArrayEquals(payload, result.bytes(1024));
    }

    @Test
    public void bytesShouldThrowWhenStreamExceedsCap() {
        HttpConsumedResult result =
                resultWith(new byte[2048], MediaType.IMAGE_JPEG, "binary", null);

        BinarySizeExceededException ex =
                assertThrows(BinarySizeExceededException.class, () -> result.bytes(1024));

        assertEquals(1024L, ex.getMaxBytes());
        assertTrue(ex.getSizeBytes() > 1024);
    }

    @Test
    public void bytesShouldThrowWhenAdvertisedSizeExceedsCap() {
        byte[] payload = new byte[64];
        Response response = new Response(new Request());
        // expectedSize advertises 5000 bytes, far above the 1024 cap -> fail before reading.
        response.setEntity(new ByteArrayRepresentation(payload, 0, payload.length,
                MediaType.APPLICATION_PDF, 5000L));
        HttpConsumedResult result = resultWith(op("binary", null), response);

        BinarySizeExceededException ex =
                assertThrows(BinarySizeExceededException.class, () -> result.bytes(1024));

        assertEquals(5000L, ex.getSizeBytes());
        assertEquals(1024L, ex.getMaxBytes());
    }

    @Test
    public void bytesShouldReturnNullWhenNoEntity() throws Exception {
        HttpConsumedResult result =
                resultWith(new HttpClientOperationSpec(), new Response(new Request()));

        assertNull(result.bytes(1024));
    }

    @Test
    public void bytesShouldBeIdempotent() throws Exception {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertSame(result.bytes(1024), result.bytes(1024));
    }

    @Test
    public void isBinaryShouldBeTrueWhenOutputRawFormatIsBinary() {
        assertTrue(resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null).operation()
                .isBinary());
    }

    @Test
    public void isBinaryShouldBeCaseInsensitive() {
        assertTrue(resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "BINARY", null).operation()
                .isBinary());
    }

    @Test
    public void isBinaryShouldBeFalseForTextFormats() {
        assertFalse(resultWith(PNG_MAGIC, MediaType.APPLICATION_XML, "xml", null).operation()
                .isBinary());
    }

    @Test
    public void isBinaryShouldBeFalseWhenNoOperation() {
        assertFalse(resultWith(null, new Response(new Request())).operation().isBinary());
        assertSame(ConsumedOperationView.NONE,
                resultWith(null, new Response(new Request())).operation());
    }

    @Test
    public void mediaTypeShouldPreferDeclaredOutputMediaTypeOverUpstream() throws Exception {
        // Upstream lies with octet-stream; declared image/jpeg must win (§4.3.1 step 1).
        HttpConsumedResult result = resultWith(PNG_MAGIC,
                MediaType.APPLICATION_OCTET_STREAM, "binary", "image/jpeg");

        result.bytes(1024);

        assertEquals("image/jpeg", result.mediaType());
    }

    @Test
    public void mediaTypeShouldUseSpecificUpstreamWhenNoDeclaredType() throws Exception {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        result.bytes(1024);

        assertEquals("image/png", result.mediaType());
    }

    @Test
    public void mediaTypeShouldFallBackToOctetStreamWhenUpstreamIsGenericAndNothingDeclared()
            throws Exception {
        HttpConsumedResult result = resultWith(PNG_MAGIC,
                MediaType.APPLICATION_OCTET_STREAM, "binary", null);

        result.bytes(1024);

        assertEquals("application/octet-stream", result.mediaType());
    }

    @Test
    public void maxBinaryBytesShouldPreferOperationOverAdapter() {
        HttpClientOperationSpec op = op("binary", null);
        op.setMaxBinarySize("5MiB");
        Response response = new Response(new Request());
        response.setEntity(new ByteArrayRepresentation(PNG_MAGIC, MediaType.IMAGE_PNG));

        assertEquals(5L * 1024 * 1024,
                resultWith(op, response).operation().maxBinaryBytes("25MiB"));
    }

    @Test
    public void maxBinaryBytesShouldUseAdapterWhenOperationUnset() {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertEquals(25L * 1024 * 1024, result.operation().maxBinaryBytes("25MiB"));
    }

    @Test
    public void maxBinaryBytesShouldUseEngineDefaultWhenNothingDeclared() {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertEquals(BinarySize.DEFAULT_MAX_BINARY_SIZE_BYTES,
                result.operation().maxBinaryBytes(null));
    }

    @Test
    public void operationCapShouldBeEnforcedWhenReadingBytes() {
        HttpClientOperationSpec op = op("binary", null);
        op.setMaxBinarySize("1KiB");
        Response response = new Response(new Request());
        response.setEntity(new ByteArrayRepresentation(new byte[4096], MediaType.IMAGE_JPEG));
        HttpConsumedResult result = resultWith(op, response);

        assertThrows(BinarySizeExceededException.class,
                () -> result.bytes(result.operation().maxBinaryBytes(null)));
    }

    @Test
    public void textResponseShouldStillBeReadableAsTextWhenNotBinary() throws Exception {
        // Sanity: the binary core does not interfere with the existing text path.
        Response response = new Response(new Request());
        response.setEntity(new StringRepresentation("hello"));
        HttpConsumedResult result = resultWith(new HttpClientOperationSpec(), response);

        assertFalse(result.operation().isBinary());
        assertEquals("hello", result.text());
    }

    @Test
    public void textShouldBeMemoizedAndLeaveEntityForwardable() throws Exception {
        Response response = new Response(new Request());
        response.setEntity(new ByteArrayRepresentation("{\"a\":1}".getBytes(),
                MediaType.APPLICATION_JSON));
        HttpConsumedResult result = resultWith(new HttpClientOperationSpec(), response);

        assertEquals("{\"a\":1}", result.text());
        assertEquals("{\"a\":1}", result.text());
        assertEquals("{\"a\":1}", result.restletEntity().getText());
        assertEquals(MediaType.APPLICATION_JSON, result.restletEntity().getMediaType());
    }

    @Test
    public void documentShouldConvertFromDeclaredRawFormat() throws Exception {
        Response response = new Response(new Request());
        response.setEntity("<root><id>7</id></root>", MediaType.APPLICATION_XML);
        HttpConsumedResult result = resultWith(op("xml", null), response);

        assertEquals("7", result.document().path("id").asText());
    }

    @Test
    public void documentShouldBeNullForBinaryOperation() throws Exception {
        HttpConsumedResult result = resultWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertNull(result.document());
    }
}
