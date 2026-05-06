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
package io.naftiko.spec.consumes.http;

import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP Digest Authentication Specification Element.
 *
 * <h2>Thread safety</h2>
 * Credential fields are stored in {@link AtomicReference}s so they can be rotated atomically
 * at runtime (token refresh, Control-port credential update). The {@code char[]} password is
 * defensively cloned on both {@code set} and {@code get} so callers cannot mutate the stored
 * credential in place. This satisfies SonarQube rule {@code java:S3077} and aligns with the
 * blueprint's "atomic password storage" pattern.
 */
public class DigestAuthenticationSpec extends AuthenticationSpec {

    private final AtomicReference<String> username = new AtomicReference<>();
    private final AtomicReference<char[]> password = new AtomicReference<>();

    public DigestAuthenticationSpec() {
        this(null, null);
    }

    public DigestAuthenticationSpec(String username, char[] password) {
        super("digest");
        this.username.set(username);
        this.password.set(password == null ? null : password.clone());
    }

    public String getUsername() {
        return username.get();
    }

    public void setUsername(String username) {
        this.username.set(username);
    }

    public char[] getPassword() {
        char[] current = password.get();
        return current == null ? null : current.clone();
    }

    public void setPassword(char[] password) {
        this.password.set(password == null ? null : password.clone());
    }

}
