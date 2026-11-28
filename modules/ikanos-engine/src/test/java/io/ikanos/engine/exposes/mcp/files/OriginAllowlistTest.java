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
package io.ikanos.engine.exposes.mcp.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.exposes.mcp.McpFilesSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;

class OriginAllowlistTest {

    private static McpServerSpec server(List<String> origins) {
        McpServerSpec spec = new McpServerSpec(null, 0, "files-mcp", null);
        McpFilesSpec files = new McpFilesSpec();
        if (origins != null) {
            files.setAllowedOrigins(origins);
        }
        spec.setFiles(files);
        return spec;
    }

    @Test
    void ofShouldReturnNullWhenTheAdapterDeclaresNoList() {
        assertNull(OriginAllowlist.of(server(null)));
        assertNull(OriginAllowlist.of(new McpServerSpec(null, 0, "files-mcp", null)));
    }

    @Test
    void allowsShouldMatchTheOriginWhateverItsCaseAndDefaultPort() {
        OriginAllowlist allowlist = OriginAllowlist.of(server(List.of(
                "https://My-Bucket.s3.eu-west-1.amazonaws.com", "http://localhost:9000/")));

        assertTrue(allowlist.allows("https://my-bucket.s3.eu-west-1.amazonaws.com/k?X-Sig=1"));
        assertTrue(allowlist.allows("HTTPS://my-bucket.s3.eu-west-1.amazonaws.com:443/k"));
        assertTrue(allowlist.allows("http://localhost:9000/bucket/k"));
    }

    @Test
    void allowsShouldRefuseAnyOtherOrigin() {
        OriginAllowlist allowlist = OriginAllowlist.of(server(List.of(
                "https://storage.example.com")));

        assertFalse(allowlist.allows("https://storage.example.com.evil.test/k"));
        assertFalse(allowlist.allows("https://storage.example.com:8443/k"));
        assertFalse(allowlist.allows("http://storage.example.com/k"));
        assertFalse(allowlist.allows("https://user@storage.example.com/k"));
        assertFalse(allowlist.allows("/files/relative"));
        assertFalse(allowlist.allows("not a url"));
        assertFalse(allowlist.allows(null));
    }

    @Test
    void ofShouldRefuseAnEntryThatIsNotAnOrigin() {
        for (String entry : List.of("https://storage.example.com/bucket", "ftp://files.test",
                "storage.example.com", "https://storage.example.com?x=1")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> OriginAllowlist.of(server(List.of(entry))), entry);
            assertTrue(e.getMessage().contains("files.allowedOrigins"), e.getMessage());
        }
    }
}
