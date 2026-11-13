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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.util.BindingKeysSpec;
import io.ikanos.spec.util.ExecutionContext;
import io.ikanos.engine.LogCapture;

public class BindingResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void resolveShouldLoadJsonAndYamlFileBindings() throws Exception {
        Path jsonFile = tempDir.resolve("secrets.json");
        Files.writeString(jsonFile, """
                {"NOTION_TOKEN":"json-token"}
                """);

        Path yamlFile = tempDir.resolve("secrets.yaml");
        Files.writeString(yamlFile, """
                API_KEY: yaml-key
                """);

        BindingResolver resolver = new BindingResolver();
        Map<String, String> resolved = resolver.resolve(List.of(
                fileBind(jsonFile, Map.of("NOTION_TOKEN", "NOTION_TOKEN")),
                fileBind(yamlFile, Map.of("API_KEY", "API_KEY"))),
                key -> null);

        assertEquals("json-token", resolved.get("NOTION_TOKEN"));
        assertEquals("yaml-key", resolved.get("API_KEY"));
    }

    // #942: a template may name a binding by its key, {{CRM_TOKEN}}, or by its bind's namespace and
    // key, {{secrets.CRM_TOKEN}}, as the examples, the schema and the rules teach.

    @Test
    public void resolveBindingsShouldOfferEachKeyFlatAndUnderItsNamespace() throws Exception {
        Path file = tempDir.resolve("secrets.yaml");
        Files.writeString(file, "crm-token: s3cr3t\n");

        Map<String, Object> bindings = new BindingResolver().resolveBindings(
                List.of(fileBind("secrets", file, Map.of("CRM_TOKEN", "crm-token"))), key -> null);

        assertEquals("s3cr3t", Resolver.resolveMustacheTemplate("{{CRM_TOKEN}}", bindings));
        assertEquals("s3cr3t", Resolver.resolveMustacheTemplate("{{secrets.CRM_TOKEN}}", bindings));
        assertEquals("sk-s3cr3t", Resolver.resolveMustacheTemplate("sk-{{secrets.CRM_TOKEN}}", bindings));
        assertEquals(List.of(), Resolver.emptyVariables("{{secrets.CRM_TOKEN}}", bindings));
    }

    @Test
    public void resolveBindingsShouldNameAMisspelledNamespaceOrKeyAsEmpty() throws Exception {
        Path file = tempDir.resolve("secrets.yaml");
        Files.writeString(file, "crm-token: s3cr3t\n");

        Map<String, Object> bindings = new BindingResolver().resolveBindings(
                List.of(fileBind("secrets", file, Map.of("CRM_TOKEN", "crm-token"))), key -> null);

        assertEquals(List.of("secret.CRM_TOKEN"),
                Resolver.emptyVariables("{{secret.CRM_TOKEN}}", bindings));
        assertEquals(List.of("secrets.CRM_TOKN"),
                Resolver.emptyVariables("{{secrets.CRM_TOKN}}", bindings));
    }

    @Test
    public void resolveBindingsShouldOfferRuntimeBindingsUnderTheirNamespaceToo() throws Exception {
        BindingSpec bind = runtimeBind(Map.of("CUSTOM_DOMAIN", "CUSTOM_DOMAIN"));
        bind.setNamespace("deploy");

        Map<String, Object> bindings = new BindingResolver().resolveBindings(List.of(bind),
                key -> "CUSTOM_DOMAIN".equals(key) ? "shop.example.com" : null);

        assertEquals("shop.example.com",
                Resolver.resolveMustacheTemplate("{{deploy.CUSTOM_DOMAIN}}", bindings));
        assertEquals("shop.example.com",
                Resolver.resolveMustacheTemplate("{{CUSTOM_DOMAIN}}", bindings));
    }

    /** Two binds with the same key name: the namespaced form tells them apart. */
    @Test
    public void resolveBindingsShouldKeepEachNamespaceItsOwnValue() throws Exception {
        Path crm = tempDir.resolve("crm.yaml");
        Files.writeString(crm, "token: crm-value\n");
        Path billing = tempDir.resolve("billing.yaml");
        Files.writeString(billing, "token: billing-value\n");

        Map<String, Object> bindings = new BindingResolver().resolveBindings(List.of(
                fileBind("crm", crm, Map.of("TOKEN", "token")),
                fileBind("billing", billing, Map.of("TOKEN", "token"))), key -> null);

        assertEquals("crm-value", Resolver.resolveMustacheTemplate("{{crm.TOKEN}}", bindings));
        assertEquals("billing-value", Resolver.resolveMustacheTemplate("{{billing.TOKEN}}", bindings));
        assertEquals("billing-value", Resolver.resolveMustacheTemplate("{{TOKEN}}", bindings),
                "the flat form keeps today's rule: the later bind wins");
    }

    /**
     * A key named like a namespace keeps its flat value, so nothing that resolves today changes; the
     * namespaced form of that bind is not offered, and a warning says so, without any value.
     */
    @Test
    public void resolveBindingsShouldKeepAFlatKeyNamedLikeANamespace() throws Exception {
        Path secrets = tempDir.resolve("secrets.yaml");
        Files.writeString(secrets, "token: s3cr3t\n");
        Path other = tempDir.resolve("other.yaml");
        Files.writeString(other, "value: plain\n");

        try (LogCapture logs = new LogCapture()) {
            Map<String, Object> bindings = new BindingResolver().resolveBindings(List.of(
                    fileBind("secrets", secrets, Map.of("TOKEN", "token")),
                    fileBind("other", other, Map.of("secrets", "value"))), key -> null);

            assertEquals("plain", Resolver.resolveMustacheTemplate("{{secrets}}", bindings));
            assertEquals("s3cr3t", Resolver.resolveMustacheTemplate("{{TOKEN}}", bindings));
            assertTrue(logs.messages().stream().anyMatch(m -> m.contains("'secrets'")
                    && m.contains("{{TOKEN}}")), logs.messages().toString());
            assertFalse(logs.messages().stream().anyMatch(m -> m.contains("s3cr3t")),
                    "no log shows a bound value");
        }
    }

    @Test
    public void resolveShouldStayFlat() throws Exception {
        Path file = tempDir.resolve("secrets.yaml");
        Files.writeString(file, "crm-token: s3cr3t\n");

        Map<String, String> resolved = new BindingResolver().resolve(
                List.of(fileBind("secrets", file, Map.of("CRM_TOKEN", "crm-token"))), key -> null);

        assertEquals(Map.of("CRM_TOKEN", "s3cr3t"), resolved);
    }

    @Test
    public void resolveFileBindingShouldFailWhenMappedKeyIsMissing() throws Exception {
        Path jsonFile = tempDir.resolve("missing.json");
        Files.writeString(jsonFile, "{}\n");

        BindingResolver resolver = new BindingResolver();

        IOException error = assertThrows(IOException.class,
                () -> resolver.resolveFileBinding(
                        fileBind(jsonFile, Map.of("NOTION_TOKEN", "NOTION_TOKEN"))));

        assertEquals("Invalid bind: key 'NOTION_TOKEN' not found in file",
                error.getMessage());
    }

    @Test
    public void resolveRuntimeBindingShouldUseExecutionContextVariables() throws Exception {
        BindingResolver resolver = new BindingResolver();

        Map<String, String> resolved = resolver.resolve(
                List.of(runtimeBind(Map.of("API_KEY", "API_KEY"))),
                new ExecutionContext() {
                    @Override
                    public String getVariable(String key) {
                        return "API_KEY".equals(key) ? "runtime-value" : null;
                    }
                });

        assertEquals("runtime-value", resolved.get("API_KEY"));
    }

    @Test
    public void resolveRuntimeBindingShouldFailWhenVariableIsMissing() {
        BindingResolver resolver = new BindingResolver();

        IOException error = assertThrows(IOException.class,
                () -> resolver.resolveRuntimeBinding(runtimeBind(Map.of("API_KEY", "API_KEY")),
                        key -> null));

        assertEquals("Invalid bind: context variable 'API_KEY' not found",
                error.getMessage());
    }

    @Test
    public void resolveFileBindingShouldSupportRelativePathWithDotSlash() throws Exception {
        // Simulate file:///./subdir/secrets.yaml resolved against user.dir (cwd)
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path subDir = cwd.resolve("test-binds-tmp");
        Files.createDirectories(subDir);
        Path secretsFile = subDir.resolve("secrets.yaml");
        Files.writeString(secretsFile, "my-token: relative-value\n");

        try {
            BindingSpec bind = new BindingSpec();
            bind.setLocation("file:///./test-binds-tmp/secrets.yaml");
            bind.setKeys(keysSpec(Map.of("TOKEN", "my-token")));

            BindingResolver resolver = new BindingResolver();
            Map<String, String> resolved = resolver.resolveFileBinding(bind);

            assertEquals("relative-value", resolved.get("TOKEN"));
        } finally {
            Files.deleteIfExists(secretsFile);
            Files.deleteIfExists(subDir);
        }
    }

    private static BindingSpec fileBind(Path path, Map<String, String> keys) {
        BindingSpec bind = new BindingSpec();
        bind.setLocation(toResolverFriendlyFileUri(path));
        bind.setKeys(keysSpec(keys));
        return bind;
    }

    private static BindingSpec fileBind(String namespace, Path path, Map<String, String> keys) {
        BindingSpec bind = fileBind(path, keys);
        bind.setNamespace(namespace);
        return bind;
    }

    private static BindingSpec runtimeBind(Map<String, String> keys) {
        BindingSpec bind = new BindingSpec();
        bind.setKeys(keysSpec(keys));
        return bind;
    }

    private static BindingKeysSpec keysSpec(Map<String, String> keys) {
        BindingKeysSpec spec = new BindingKeysSpec();
        spec.setKeys(keys);
        return spec;
    }

    private static String toResolverFriendlyFileUri(Path path) {
        return path.toAbsolutePath().toUri().toString();
    }
}
