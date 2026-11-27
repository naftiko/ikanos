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
package io.ikanos.engine.consumes.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.spec.consumes.ContractClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcProcedureSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;

/**
 * Unit tests for the shared base of the contract-described consumed adapters, through a minimal
 * subclass whose contract says which operations only read.
 */
class ContractClientAdapterTest {

    @TempDir
    Path root;

    private Path dir;
    private Path base;
    private final Map<String, Object> bindings = new HashMap<>();

    /** A minimal adapter: the contract says {@code get-order} reads, every other writes. */
    private static final class TestAdapter extends ContractClientAdapter {

        TestAdapter(Capability capability, ContractClientSpec spec) {
            super(capability, spec, Map.of());
        }

        @Override
        protected boolean contractReadOnly(String operationName) {
            return "get-order".equals(operationName);
        }

        @Override
        public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
            return null;
        }

        @Override
        public void start() {
            // nothing to start
        }

        @Override
        public void stop() {
            // nothing to stop
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(root.resolve("capability"));
        base = dir.toAbsolutePath().normalize();
    }

    private TestAdapter adapter(AuthenticationSpec authentication) {
        GrpcClientSpec spec = new GrpcClientSpec();
        spec.setNamespace("orders");
        spec.setAuthentication(authentication);
        spec.getProcedures().put("get-order", procedure("get-order", null));
        spec.getProcedures().put("list-orders", procedure("list-orders", true));
        spec.getProcedures().put("cancel-order", procedure("cancel-order", null));
        spec.getProcedures().put("peek-order", procedure("peek-order", false));
        Capability capability = mock(Capability.class);
        when(capability.getCapabilityDir()).thenReturn(dir);
        when(capability.getBindings()).thenReturn(bindings);
        return new TestAdapter(capability, spec);
    }

    private static GrpcProcedureSpec procedure(String name, Boolean readOnly) {
        GrpcProcedureSpec procedure = new GrpcProcedureSpec();
        procedure.setName(name);
        procedure.setReadOnly(readOnly);
        return procedure;
    }

    // ── contractPath ──

    @Test
    void contractPathShouldResolveARelativePathAgainstTheCapabilityDirectory() {
        TestAdapter adapter = adapter(null);

        assertEquals(base.resolve("contracts/orders.binpb"),
                adapter.contractPath("descriptorSet", "contracts/orders.binpb"));
        assertEquals(base.resolve("contracts/orders.binpb"),
                adapter.contractPath("descriptorSet", "./contracts/../contracts/orders.binpb"));
    }

    @Test
    void contractPathShouldAcceptARelativeFileUri() {
        assertEquals(base.resolve("contracts/orders.binpb"),
                adapter(null).contractPath("descriptorSet", "file:///./contracts/orders.binpb"));
    }

    @Test
    void contractPathShouldRefuseAPathThatLeavesTheDirectory() {
        TestAdapter adapter = adapter(null);

        for (String path : new String[] {"../orders.binpb", "contracts/../../orders.binpb",
                "file:///../orders.binpb"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> adapter.contractPath("descriptorSet", path), path);
            assertTrue(e.getMessage().contains("inside the capability's directory"),
                    e.getMessage());
        }
    }

    @Test
    void contractPathShouldRefuseAnAbsolutePath() {
        TestAdapter adapter = adapter(null);
        String inside = base.resolve("orders.binpb").toString();

        assertThrows(IllegalArgumentException.class,
                () -> adapter.contractPath("descriptorSet", inside),
                "an absolute path is refused, even inside the directory");
        assertThrows(IllegalArgumentException.class,
                () -> adapter.contractPath("descriptorSet", "/etc/passwd"));
    }

    @Test
    void contractPathShouldRefuseAnAbsoluteFileUri() {
        TestAdapter adapter = adapter(null);

        assertThrows(IllegalArgumentException.class,
                () -> adapter.contractPath("descriptorSet", "file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.contractPath("descriptorSet", "file:///C:/Windows/win.ini"));
    }

    @Test
    void contractPathShouldRefuseARemoteUri() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter(null).contractPath("descriptorSet",
                        "https://example.com/orders.binpb"));

        assertTrue(e.getMessage().contains("never fetched"), e.getMessage());
    }

    @Test
    void contractPathShouldRefuseAnEmptyPath() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter(null).contractPath("descriptorSet", " "));
    }

    @Test
    void contractPathShouldResolvePlaceholdersFromTheBindings() {
        bindings.put("CONTRACTS", "contracts");

        assertEquals(base.resolve("contracts/orders.binpb"),
                adapter(null).contractPath("descriptorSet", "{{CONTRACTS}}/orders.binpb"));
    }

    @Test
    void contractPathShouldRefuseASymbolicLinkThatPointsOutside() throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.binpb"), "secret");
        try {
            Files.createSymbolicLink(dir.resolve("orders.binpb"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links are not available here: " + e.getMessage());
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter(null).contractPath("descriptorSet", "orders.binpb"));

        assertTrue(e.getMessage().contains("links outside"), e.getMessage());
    }

    // ── readContract ──

    @Test
    void readContractShouldReadAFileInsideTheDirectory() throws IOException {
        Files.createDirectories(dir.resolve("contracts"));
        Files.writeString(dir.resolve("contracts/orders.binpb"), "contract");

        assertArrayEquals("contract".getBytes(StandardCharsets.UTF_8),
                adapter(null).readContract("descriptorSet", "contracts/orders.binpb"));
    }

    @Test
    void readContractShouldRefuseAFileLargerThanTheCap() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(
                dir.resolve("huge.binpb").toFile(), "rw")) {
            file.setLength(ContractClientAdapter.MAX_CONTRACT_BYTES + 1);
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter(null).readContract("descriptorSet", "huge.binpb"));

        assertTrue(e.getMessage().contains("more than "
                + ContractClientAdapter.MAX_CONTRACT_BYTES), e.getMessage());
    }

    @Test
    void readContractShouldRefuseADirectory() throws IOException {
        Files.createDirectories(dir.resolve("contracts"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter(null).readContract("descriptorSet", "contracts"));

        assertTrue(e.getMessage().contains("not a regular file"), e.getMessage());
    }

    @Test
    void readContractShouldRefuseAMissingFile() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter(null).readContract("descriptorSet", "missing.binpb"));
    }

    // ── credentialHeaders ──

    @Test
    void credentialHeadersShouldBeEmptyWithoutAuthentication() {
        assertTrue(adapter(null).credentialHeaders().isEmpty());
    }

    @Test
    void credentialHeadersShouldRenderBasic() {
        bindings.put("PASSWORD", "s3cret");

        Map<String, String> headers = adapter(new BasicAuthenticationSpec("ada",
                "{{PASSWORD}}".toCharArray())).credentialHeaders();

        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "ada:s3cret".getBytes(StandardCharsets.UTF_8)), headers.get("Authorization"));
    }

    @Test
    void credentialHeadersShouldRenderBearer() {
        bindings.put("TOKEN", "abc");

        assertEquals(Map.of("Authorization", "Bearer abc"),
                adapter(new BearerAuthenticationSpec("{{TOKEN}}")).credentialHeaders());
    }

    @Test
    void credentialHeadersShouldRenderAnApiKeyInAHeader() {
        assertEquals(Map.of("X-Api-Key", "k1"), adapter(new ApiKeyAuthenticationSpec(
                "X-Api-Key", "header", "k1")).credentialHeaders());
        assertEquals(Map.of("X-Api-Key", "k1"), adapter(new ApiKeyAuthenticationSpec(
                "X-Api-Key", null, "k1")).credentialHeaders(), "a header when omitted");
    }

    @Test
    void credentialHeadersShouldRefuseACredentialThatIsNoHeader() {
        TestAdapter query = adapter(new ApiKeyAuthenticationSpec("key", "query", "k1"));
        TestAdapter digest = adapter(new DigestAuthenticationSpec("ada", "pw".toCharArray()));

        assertThrows(IllegalArgumentException.class, query::credentialHeaders);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                digest::credentialHeaders);
        assertTrue(e.getMessage().contains("digest"), e.getMessage());
    }

    @Test
    void credentialHeadersShouldRefuseAnEmptyToken() {
        bindings.put("TOKEN", "");

        assertThrows(IllegalArgumentException.class,
                () -> adapter(new BearerAuthenticationSpec("{{TOKEN}}")).credentialHeaders());
    }

    // ── isLoopback ──

    @Test
    void isLoopbackShouldAcceptLoopbackNamesAndLiterals() {
        for (String host : new String[] {"localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3",
                "127.255.255.255", "::1", "[::1]"}) {
            assertTrue(ContractClientAdapter.isLoopback(host), host);
        }
    }

    @Test
    void isLoopbackShouldRefuseOtherHosts() {
        for (String host : new String[] {"127.example.com", "127.0.0.1.nip.io", "127.0.0",
                "127.256.0.1", "10.0.0.1", "example.com", "::2", ""}) {
            assertFalse(ContractClientAdapter.isLoopback(host), host);
        }
        assertFalse(ContractClientAdapter.isLoopback(null));
    }

    // ── readOnly ──

    @Test
    void isReadOnlyShouldPreferTheDeclaredValueOverTheContract() {
        TestAdapter adapter = adapter(null);

        assertTrue(adapter.isReadOnly("get-order"), "derived from the contract");
        assertFalse(adapter.isReadOnly("cancel-order"), "derived from the contract");
        assertTrue(adapter.isReadOnly("list-orders"), "declared");
        assertFalse(adapter.isReadOnly("peek-order"), "declared, though the contract may differ");
        assertFalse(adapter.isReadOnly("unknown"));
    }

    @Test
    void isWriteOperationShouldBeADeclaredOperationThatIsNotReadOnly() {
        TestAdapter adapter = adapter(null);

        assertTrue(adapter.isWriteOperation("cancel-order"));
        assertTrue(adapter.isWriteOperation("peek-order"));
        assertFalse(adapter.isWriteOperation("get-order"));
        assertFalse(adapter.isWriteOperation("unknown"), "not declared");
    }

    @Test
    void checkReadOnlyShouldRefuseADeclaredReadOnlyThatTheContractContradicts() {
        TestAdapter adapter = adapter(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter.checkReadOnly("list-orders", false, false));
        assertTrue(e.getMessage().contains("'list-orders'"), e.getMessage());
        assertDoesNotThrow(() -> adapter.checkReadOnly("list-orders", false, true),
                "a contract that cannot tell is trusted");
        assertDoesNotThrow(() -> adapter.checkReadOnly("list-orders", true, false));
        assertDoesNotThrow(() -> adapter.checkReadOnly("cancel-order", false, false),
                "not declared read-only");
    }
}
