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
package io.ikanos.cli;

import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Preference;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.representation.StringRepresentation;

/**
 * Lightweight HTTP client for connecting to a running Ikanos control port. Uses Restlet's
 * {@link Client}, consistent with the engine's {@code HttpClientAdapter}.
 */
class ControlPortClient {

    private final String baseUrl;
    private final Client httpClient;

    ControlPortClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new Client(Protocol.HTTP);
    }

    /**
     * Sends a GET request accepting JSON to the given path.
     *
     * @throws ControlPortUnreachableException if the control port cannot be reached
     */
    ControlPortResponse get(String path) throws ControlPortUnreachableException {
        Request request = new Request(Method.GET, new Reference(baseUrl + path));
        request.getClientInfo().getAcceptedMediaTypes()
                .add(new Preference<>(MediaType.APPLICATION_JSON));
        return execute(request);
    }

    /**
     * Sends a GET request accepting any content type (used for Prometheus metrics).
     *
     * @throws ControlPortUnreachableException if the control port cannot be reached
     */
    ControlPortResponse getPlain(String path) throws ControlPortUnreachableException {
        Request request = new Request(Method.GET, new Reference(baseUrl + path));
        return execute(request);
    }

    /**
     * Sends a PUT request with a JSON body to the given path.
     *
     * @throws ControlPortUnreachableException if the control port cannot be reached
     */
    ControlPortResponse put(String path, String jsonBody) throws ControlPortUnreachableException {
        Request request = new Request(Method.PUT, new Reference(baseUrl + path));
        request.setEntity(new StringRepresentation(jsonBody, MediaType.APPLICATION_JSON));
        request.getClientInfo().getAcceptedMediaTypes()
                .add(new Preference<>(MediaType.APPLICATION_JSON));
        return execute(request);
    }

    private ControlPortResponse execute(Request request) throws ControlPortUnreachableException {
        try {
            Response response = httpClient.handle(request);
            Status status = response.getStatus();

            if (status.isConnectorError()) {
                throw new ControlPortUnreachableException(baseUrl,
                        new java.io.IOException(status.getDescription()));
            }

            String body = response.getEntity() != null ? response.getEntity().getText() : "";
            return new ControlPortResponse(status.getCode(), body);
        } catch (ControlPortUnreachableException e) {
            throw e;
        } catch (Exception e) {
            throw new ControlPortUnreachableException(baseUrl, e);
        }
    }

    record ControlPortResponse(int statusCode, String body) {
    }

    static class ControlPortUnreachableException extends Exception {

        ControlPortUnreachableException(String baseUrl, Throwable cause) {
            super("Cannot connect to control port at " + baseUrl, cause);
        }
    }
}
