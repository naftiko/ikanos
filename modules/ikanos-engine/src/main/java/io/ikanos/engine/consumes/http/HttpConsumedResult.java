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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.engine.consumes.RestletBackedResult;
import io.ikanos.engine.util.ConversionFormat;
import io.ikanos.engine.util.Converter;
import io.ikanos.engine.util.OperationStepExecutor.BinarySizeExceededException;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;

/**
 * {@link ConsumedResult} backed by a Restlet {@link Response} from a consumed HTTP operation.
 *
 * <p>This class holds what {@code OperationStepExecutor.HandlingContext} used to expose as public
 * fields. The text, binary and media-type behaviour is unchanged:</p>
 *
 * <ul>
 *   <li>{@link #text()} buffers the entity once. As before, a non-{@link StringRepresentation}
 *       entity is replaced by a {@link StringRepresentation} of the same text, so the REST exposer
 *       can still forward it after it has been read.</li>
 *   <li>{@link #bytes(long)} is the bounded binary read from {@code capability-binary-content.md}
 *       (fail fast on an advertised size above the cap, enforce the cap while reading, idempotent).
 *   </li>
 *   <li>{@link #mediaType()} for a binary operation applies the §4.3.1 precedence.</li>
 * </ul>
 */
public class HttpConsumedResult implements ConsumedResult, RestletBackedResult {

    private final HttpClientOperationSpec operationSpec;
    private final ConsumedOperationView operation;
    private final Response response;

    private String text;
    private boolean textRead;
    private JsonNode document;
    private boolean documentRead;
    private byte[] bytes;

    /**
     * @param operationSpec the consumed operation, or {@code null} when none is bound
     * @param response      the Restlet response, filled by the HTTP client
     */
    public HttpConsumedResult(HttpClientOperationSpec operationSpec, Response response) {
        this.operationSpec = operationSpec;
        this.operation = operationSpec != null
                ? ConsumedOperationView.of(operationSpec, operationSpec.getOutputMediaType(),
                        operationSpec.getMaxBinarySize())
                : ConsumedOperationView.NONE;
        this.response = response;
    }

    /** @return the consumed operation spec, or {@code null} */
    public HttpClientOperationSpec getOperationSpec() {
        return operationSpec;
    }

    /** @return the underlying Restlet response */
    public Response getResponse() {
        return response;
    }

    /** @return the Restlet request that produced this result, or {@code null} */
    public Request getRequest() {
        return response != null ? response.getRequest() : null;
    }

    private Representation entity() {
        return response != null ? response.getEntity() : null;
    }

    @Override
    public ConsumedOperationView operation() {
        return operation;
    }

    @Override
    public Outcome outcome() {
        return Outcome.fromHttpStatus(status());
    }

    @Override
    public int status() {
        Status status = restletStatus();
        return status != null ? status.getCode() : 0;
    }

    @Override
    public String reason() {
        Status status = restletStatus();
        return status != null ? status.getReasonPhrase() : null;
    }

    @Override
    public boolean hasBody() {
        Representation entity = entity();
        return entity != null && !(entity instanceof EmptyRepresentation);
    }

    @Override
    public String mediaType() {
        if (operation.isBinary()) {
            return resolveBinaryMediaType();
        }
        Representation entity = entity();
        return entity != null && entity.getMediaType() != null
                ? entity.getMediaType().getName()
                : null;
    }

    @Override
    public String text() throws IOException {
        if (textRead) {
            return text;
        }
        Representation entity = entity();
        if (entity == null) {
            textRead = true;
            return null;
        }
        text = entity.getText();
        if (!(entity instanceof StringRepresentation)) {
            response.setEntity(new StringRepresentation(text, entity.getMediaType()));
        }
        textRead = true;
        return text;
    }

    /**
     * Converts the body to JSON using the operation's {@code outputRawFormat}. Protobuf and Avro
     * are read from the stream when the text has not been buffered yet, as the REST exposer did
     * before; every other format is converted from {@link #text()}.
     */
    @Override
    public JsonNode document() throws IOException {
        if (documentRead) {
            return document;
        }
        Representation entity = entity();
        if (entity == null || operation.isBinary()) {
            documentRead = true;
            return null;
        }
        String format = operation.outputRawFormat();
        String schema = operation.outputSchema();
        ConversionFormat fmt = ConversionFormat.fromDisplay(format);
        if (!textRead && (fmt == ConversionFormat.PROTOBUF || fmt == ConversionFormat.AVRO)) {
            document = Converter.convertToJson(format, schema, entity);
        } else {
            String body = text();
            document = body == null || body.isEmpty()
                    ? null
                    : Converter.convertToJson(format, schema, body);
        }
        documentRead = true;
        return document;
    }

    @Override
    public byte[] bytes(long maxBytes) throws IOException {
        if (bytes != null) {
            return bytes;
        }

        Representation entity = entity();
        if (entity == null || entity.isEmpty()) {
            return null;
        }

        // Fail fast when the upstream advertises a size beyond the cap, avoiding a read.
        long advertised = entity.getSize();
        if (advertised > 0 && advertised > maxBytes) {
            throw new BinarySizeExceededException(advertised, maxBytes);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                advertised > 0 && advertised <= maxBytes ? (int) advertised : 8192);
        byte[] chunk = new byte[8192];
        long total = 0;
        try (InputStream in = entity.getStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BinarySizeExceededException(total, maxBytes);
                }
                buffer.write(chunk, 0, read);
            }
        }

        bytes = buffer.toByteArray();
        return bytes;
    }

    /**
     * Binary media type precedence (§4.3.1): declared {@code outputMediaType}, then a specific
     * upstream {@code Content-Type}, then the upstream type even if generic, then
     * {@code application/octet-stream}.
     *
     * @return the resolved media type; never {@code null}
     */
    String resolveBinaryMediaType() {
        String declared = operation.outputMediaType();
        if (declared != null && !declared.isBlank()) {
            return declared.trim();
        }

        Representation entity = entity();
        String upstream = entity != null && entity.getMediaType() != null
                ? entity.getMediaType().getName()
                : null;

        if (upstream != null && !upstream.isBlank()
                && !MediaType.APPLICATION_OCTET_STREAM.getName().equalsIgnoreCase(upstream)
                && !"binary/octet-stream".equalsIgnoreCase(upstream)) {
            return upstream;
        }

        if (upstream != null && !upstream.isBlank()) {
            return upstream;
        }

        return MediaType.APPLICATION_OCTET_STREAM.getName();
    }

    @Override
    public Representation restletEntity() {
        return entity();
    }

    @Override
    public Status restletStatus() {
        return response != null ? response.getStatus() : null;
    }
}
