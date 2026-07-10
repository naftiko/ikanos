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
package io.ikanos.engine.util;

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

import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.engine.util.OperationStepExecutor.HandlingContext;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;

/**
 * Phase&nbsp;1 tests for the reusable binary core on
 * {@link OperationStepExecutor.HandlingContext}: byte-faithful buffering, the {@code maxBinarySize}
 * cap, and the §4.3.1 media-type precedence. See {@code blueprints/capability-binary-content.md}
 * §13 (Phase&nbsp;1).
 */
public class HandlingContextBinaryTest {

    private static final byte[] PNG_MAGIC =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private HandlingContext contextWith(byte[] bytes, MediaType upstreamType, String outputRawFormat,
            String outputMediaType) {
        HttpClientOperationSpec op = new HttpClientOperationSpec();
        op.setOutputRawFormat(outputRawFormat);
        op.setOutputMediaType(outputMediaType);

        HandlingContext ctx = new HandlingContext();
        ctx.clientOperation = op;
        Request request = new Request();
        ctx.clientRequest = request;
        Response response = new Response(request);
        response.setEntity(new ByteArrayRepresentation(bytes, upstreamType));
        ctx.clientResponse = response;
        return ctx;
    }

    @Test
    public void readBoundedBytesShouldReturnExactBytesWhenUnderCap() throws Exception {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        byte[] result = ctx.readBoundedBytes(1024);

        assertArrayEquals(PNG_MAGIC, result);
        assertArrayEquals(PNG_MAGIC, ctx.clientResponseBytes);
    }

    @Test
    public void readBoundedBytesShouldNotCorruptHighBitBytes() throws Exception {
        byte[] payload = new byte[256];
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) i;
        }
        HandlingContext ctx = contextWith(payload,
                MediaType.APPLICATION_OCTET_STREAM, "binary", null);

        byte[] result = ctx.readBoundedBytes(1024);

        assertArrayEquals(payload, result);
    }

    @Test
    public void readBoundedBytesShouldThrowWhenStreamExceedsCap() {
        byte[] payload = new byte[2048];
        HandlingContext ctx = contextWith(payload, MediaType.IMAGE_JPEG, "binary", null);

        BinarySizeExceededException ex = assertThrows(BinarySizeExceededException.class,
                () -> ctx.readBoundedBytes(1024));

        assertEquals(1024L, ex.getMaxBytes());
        assertTrue(ex.getSizeBytes() > 1024);
    }

    @Test
    public void readBoundedBytesShouldThrowWhenAdvertisedSizeExceedsCap() {
        byte[] payload = new byte[64];
        HandlingContext ctx = new HandlingContext();
        HttpClientOperationSpec op = new HttpClientOperationSpec();
        op.setOutputRawFormat("binary");
        ctx.clientOperation = op;
        Request request = new Request();
        ctx.clientRequest = request;
        Response response = new Response(request);
        // expectedSize advertises 5000 bytes, far above the 1024 cap -> fail before reading.
        response.setEntity(new ByteArrayRepresentation(payload, 0, payload.length,
                MediaType.APPLICATION_PDF, 5000L));
        ctx.clientResponse = response;

        BinarySizeExceededException ex = assertThrows(BinarySizeExceededException.class,
                () -> ctx.readBoundedBytes(1024));

        assertEquals(5000L, ex.getSizeBytes());
        assertEquals(1024L, ex.getMaxBytes());
    }

    @Test
    public void readBoundedBytesShouldReturnNullWhenNoEntity() throws Exception {
        HandlingContext ctx = new HandlingContext();
        ctx.clientOperation = new HttpClientOperationSpec();
        Request request = new Request();
        ctx.clientRequest = request;
        ctx.clientResponse = new Response(request);

        assertNull(ctx.readBoundedBytes(1024));
    }

    @Test
    public void readBoundedBytesShouldBeIdempotent() throws Exception {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        byte[] first = ctx.readBoundedBytes(1024);
        byte[] second = ctx.readBoundedBytes(1024);

        assertSame(first, second);
    }

    @Test
    public void isBinaryShouldBeTrueWhenOutputRawFormatIsBinary() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);
        assertTrue(ctx.isBinary());
    }

    @Test
    public void isBinaryShouldBeCaseInsensitive() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "BINARY", null);
        assertTrue(ctx.isBinary());
    }

    @Test
    public void isBinaryShouldBeFalseForTextFormats() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.APPLICATION_XML, "xml", null);
        assertFalse(ctx.isBinary());
    }

    @Test
    public void isBinaryShouldBeFalseWhenNoOperation() {
        HandlingContext ctx = new HandlingContext();
        assertFalse(ctx.isBinary());
    }

    @Test
    public void mediaTypeShouldPreferDeclaredOutputMediaTypeOverUpstream() throws Exception {
        // Upstream lies with octet-stream; declared image/jpeg must win (§4.3.1 step 1).
        HandlingContext ctx = contextWith(PNG_MAGIC,
                MediaType.APPLICATION_OCTET_STREAM, "binary", "image/jpeg");

        ctx.readBoundedBytes(1024);

        assertEquals("image/jpeg", ctx.clientResponseMediaType);
    }

    @Test
    public void mediaTypeShouldUseSpecificUpstreamWhenNoDeclaredType() throws Exception {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        ctx.readBoundedBytes(1024);

        assertEquals("image/png", ctx.clientResponseMediaType);
    }

    @Test
    public void mediaTypeShouldFallBackToOctetStreamWhenUpstreamIsGenericAndNothingDeclared()
            throws Exception {
        HandlingContext ctx = contextWith(PNG_MAGIC,
                MediaType.APPLICATION_OCTET_STREAM, "binary", null);

        ctx.readBoundedBytes(1024);

        assertEquals("application/octet-stream", ctx.clientResponseMediaType);
    }

    @Test
    public void resolveMaxBinaryBytesShouldPreferOperationOverAdapter() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);
        ctx.clientOperation.setMaxBinarySize("5MiB");

        assertEquals(5L * 1024 * 1024, ctx.resolveMaxBinaryBytes("25MiB"));
    }

    @Test
    public void resolveMaxBinaryBytesShouldUseAdapterWhenOperationUnset() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertEquals(25L * 1024 * 1024, ctx.resolveMaxBinaryBytes("25MiB"));
    }

    @Test
    public void resolveMaxBinaryBytesShouldUseEngineDefaultWhenNothingDeclared() {
        HandlingContext ctx = contextWith(PNG_MAGIC, MediaType.IMAGE_PNG, "binary", null);

        assertEquals(BinarySize.DEFAULT_MAX_BINARY_SIZE_BYTES, ctx.resolveMaxBinaryBytes(null));
    }

    @Test
    public void noArgReadBoundedBytesShouldHonorOperationCap() {
        byte[] payload = new byte[4096];
        HandlingContext ctx = contextWith(payload, MediaType.IMAGE_JPEG, "binary", null);
        ctx.clientOperation.setMaxBinarySize("1KiB");

        assertThrows(BinarySizeExceededException.class, ctx::readBoundedBytes);
    }

    @Test
    public void textResponseShouldStillBeReadableAsTextWhenNotBinary() throws Exception {
        // Sanity: the binary core does not interfere with the existing text path.
        HandlingContext ctx = new HandlingContext();
        ctx.clientOperation = new HttpClientOperationSpec();
        Request request = new Request();
        ctx.clientRequest = request;
        Response response = new Response(request);
        response.setEntity(new StringRepresentation("hello"));
        ctx.clientResponse = response;

        assertFalse(ctx.isBinary());
        assertEquals("hello", ctx.clientResponse.getEntity().getText());
    }
}
