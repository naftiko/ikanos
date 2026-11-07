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
package io.ikanos.spec.consumes.http;

import java.util.concurrent.atomic.AtomicReference;

/**
 * AWS Signature Version 4 Authentication Specification Element, for the calls a capability makes
 * to Amazon S3, the other AWS services and the S3-compatible object stores (#954). Each request is
 * signed with the secret access key, the date, the region and the service. Consumed adapters only:
 * it can't protect an exposed adapter.
 *
 * <h2>Thread safety</h2>
 * Every field is stored in an {@link AtomicReference}, never {@code volatile} (Sonar
 * {@code java:S3077}), so credentials can be rotated atomically at runtime, as for
 * {@link BasicAuthenticationSpec}. The {@code char[]} secret access key is defensively cloned on
 * both {@code set} and {@code get}.
 */
public class AwsSigV4AuthenticationSpec extends AuthenticationSpec {

    /** The service signed when none is given. */
    public static final String DEFAULT_SERVICE = "s3";

    private final AtomicReference<String> accessKeyId = new AtomicReference<>();
    private final AtomicReference<char[]> secretAccessKey = new AtomicReference<>();
    private final AtomicReference<String> sessionToken = new AtomicReference<>();
    private final AtomicReference<String> region = new AtomicReference<>();
    private final AtomicReference<String> service = new AtomicReference<>();
    private final AtomicReference<Boolean> unsignedPayload = new AtomicReference<>();

    public AwsSigV4AuthenticationSpec() {
        super("awsSigV4");
    }

    public String getAccessKeyId() {
        return accessKeyId.get();
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId.set(accessKeyId);
    }

    public char[] getSecretAccessKey() {
        char[] current = secretAccessKey.get();
        return current == null ? null : current.clone();
    }

    public void setSecretAccessKey(char[] secretAccessKey) {
        this.secretAccessKey.set(secretAccessKey == null ? null : secretAccessKey.clone());
    }

    /** @return the session token of temporary credentials, or {@code null} */
    public String getSessionToken() {
        return sessionToken.get();
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken.set(sessionToken);
    }

    public String getRegion() {
        return region.get();
    }

    public void setRegion(String region) {
        this.region.set(region);
    }

    /** @return the service as declared, or {@code null} for {@value #DEFAULT_SERVICE} */
    public String getService() {
        return service.get();
    }

    public void setService(String service) {
        this.service.set(service);
    }

    /** @return whether S3 is sent {@code UNSIGNED-PAYLOAD} instead of the body's hash */
    public Boolean getUnsignedPayload() {
        return unsignedPayload.get();
    }

    public void setUnsignedPayload(Boolean unsignedPayload) {
        this.unsignedPayload.set(unsignedPayload);
    }

}
