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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Method;

class StandardRequestHeadersTest {

    private final Request request = new Request(Method.GET, "http://localhost/target");

    @Test
    void setShouldKeepACustomHeaderAsARawHeader() {
        StandardRequestHeaders.set(request, "X-Trace", "abc");

        assertEquals("abc", request.getHeaders().getFirstValue("X-Trace", true));
    }

    @Test
    void setShouldReplaceAStandardHeaderSetBefore() {
        StandardRequestHeaders.set(request, "Accept", "application/xml");
        StandardRequestHeaders.set(request, "accept", "application/json");

        assertEquals(1, request.getClientInfo().getAcceptedMediaTypes().size());
        assertEquals(MediaType.APPLICATION_JSON,
                request.getClientInfo().getAcceptedMediaTypes().get(0).getMetadata());
    }

    @Test
    void setShouldFailWhenAStandardHeaderValueCannotBeParsed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StandardRequestHeaders.set(request, "If-Modified-Since", "yesterday"));

        assertTrue(error.getMessage().contains("If-Modified-Since"), error.getMessage());
        assertFalse(error.getMessage().contains("yesterday"),
                "the message must not echo the value, which may be a credential");
        assertNull(request.getConditions().getModifiedSince());
    }

    @Test
    void setShouldSendAuthorizationVerbatim() {
        StandardRequestHeaders.set(request, "Authorization", "Token abc");

        assertNotNull(request.getChallengeResponse());
        assertEquals("Token abc", request.getChallengeResponse().getIdentifier());
    }

    @Test
    void notSendableReasonShouldNameTheConnectorForHost() {
        assertEquals("is set by the HTTP connector",
                StandardRequestHeaders.notSendableReason("host"));
    }

    @Test
    void notSendableReasonShouldNameTheConnectorForAcceptEncoding() {
        // The Jetty client sends its own Accept-Encoding and decodes the response: a copied value
        // went out twice ("gzip,gzip") and the caller still got a decoded body
        assertNotNull(StandardRequestHeaders.notSendableReason("Accept-Encoding"));
    }

    @Test
    void notSendableReasonShouldBeNullForASendableHeader() {
        assertNull(StandardRequestHeaders.notSendableReason("If-None-Match"));
        assertNull(StandardRequestHeaders.notSendableReason("X-Trace"));
        assertNull(StandardRequestHeaders.notSendableReason("Content-Type"));
    }

    @Test
    void isEntityHeaderShouldBeTrueForContentType() {
        assertTrue(StandardRequestHeaders.isEntityHeader("content-type"));
        assertFalse(StandardRequestHeaders.isEntityHeader("Accept"));
    }
}
