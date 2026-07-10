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

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates that the GraalVM native-image reflect-config.json contains all classes
 * required for Jackson serialization in the import openapi command.
 */
public class NativeImageReflectConfigTest {

    private static final String SPEC_PACKAGE = "io.ikanos.spec";

    private static Set<String> registeredClasses;

    @BeforeAll
    static void loadReflectConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = NativeImageReflectConfigTest.class.getClassLoader()
                .getResourceAsStream("META-INF/native-image/reflect-config.json")) {
            assertNotNull(is, "reflect-config.json must be on the classpath");
            List<ReflectEntry> entries = mapper.readValue(is,
                    new TypeReference<List<ReflectEntry>>() {});
            registeredClasses = entries.stream()
                    .map(e -> e.name)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void reflectConfigShouldContainIkanosSpecClasses() {
        List<String> requiredClasses = List.of(
                "io.ikanos.spec.IkanosSpec",
                "io.ikanos.spec.consumes.ClientSpec",
                "io.ikanos.spec.consumes.http.HttpClientSpec",
                "io.ikanos.spec.consumes.http.HttpClientResourceSpec",
                "io.ikanos.spec.consumes.http.HttpClientOperationSpec",
                "io.ikanos.spec.OperationSpec",
                "io.ikanos.spec.ResourceSpec",
                "io.ikanos.spec.InputParameterSpec",
                "io.ikanos.spec.OutputParameterSpec",
                "io.ikanos.spec.util.StructureSpec");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing Naftiko spec classes required for "
                        + "Jackson serialization in native mode: " + missing);
    }

    @Test
    void reflectConfigShouldContainAuthenticationSpecClasses() {
        List<String> requiredClasses = List.of(
                "io.ikanos.spec.consumes.http.AuthenticationSpec",
                "io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec",
                "io.ikanos.spec.consumes.http.BearerAuthenticationSpec",
                "io.ikanos.spec.consumes.http.BasicAuthenticationSpec",
                "io.ikanos.spec.consumes.http.DigestAuthenticationSpec",
                "io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing authentication spec classes: " + missing);
    }

    @Test
    void reflectConfigShouldContainCustomSerializers() {
        List<String> requiredClasses = List.of(
                "io.ikanos.spec.InputParameterSerializer",
                "io.ikanos.spec.OutputParameterSerializer");

        List<String> missing = requiredClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing custom Jackson serializers: " + missing);
    }

    /**
     * Dynamically discovers every {@link JsonDeserializer} subclass in the
     * {@code io.ikanos.spec} package tree and asserts that each one is registered
     * in {@code reflect-config.json}.
     *
     * <p>This replaces a static whitelist: new deserializers added to ikanos-spec
     * are automatically included in the check without any manual update.</p>
     */
    @Test
    void reflectConfigShouldContainAllSpecDeserializerClassesForNativeMode() throws Exception {
        List<String> deserializerClasses = findJsonDeserializerSubclasses(SPEC_PACKAGE);
        assertFalse(deserializerClasses.isEmpty(),
                "Classpath scan found no JsonDeserializer subclasses in " + SPEC_PACKAGE
                        + " — the scan logic may be broken");

        List<String> missing = deserializerClasses.stream()
                .filter(c -> !registeredClasses.contains(c))
                .sorted()
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json is missing JsonDeserializer subclasses from "
                        + SPEC_PACKAGE + " required for Jackson reflection in native mode: "
                        + missing);
    }

    /**
     * Scans the classpath for all classes in {@code packageName} (and sub-packages)
     * that are assignable to {@link JsonDeserializer}, using only the JDK class loader
     * and NIO — no third-party scanning library required.
     *
     * <p>Works for both exploded directories (IDE / {@code mvn test}) and JAR files
     * (shaded uber-jar or native-image build). The {@code JsonDeserializer.None} sentinel
     * inner class is excluded because it is not a real deserializer.</p>
     */
    static List<String> findJsonDeserializerSubclasses(String packageName) throws Exception {
        String packagePath = packageName.replace('.', '/');
        ClassLoader cl = NativeImageReflectConfigTest.class.getClassLoader();
        List<String> result = new ArrayList<>();

        Enumeration<URL> roots = cl.getResources(packagePath);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                // e.g. jar:file:/...ikanos-spec-...jar!/io/ikanos/spec
                String[] parts = uri.toString().split("!");
                URI jarUri = URI.create(parts[0]);
                try (FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {
                    Path root = fs.getPath(packagePath);
                    collectDeserializers(root, packageName, cl, result);
                }
            } else {
                // exploded directory on the file system
                Path root = Path.of(uri);
                collectDeserializers(root, packageName, cl, result);
            }
        }
        return result;
    }

    private static void collectDeserializers(
            Path root, String packageName, ClassLoader cl, List<String> result) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        // Use the package path as the known prefix instead of relativizing,
        // which avoids NPE when getParent() returns null on JAR filesystem roots.
        String packagePath = packageName.replace('.', '/');

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String fileName = p.getFileName().toString();
                if (!fileName.endsWith(".class") || fileName.contains("$")) {
                    continue;
                }
                // Normalise to forward-slash form, then extract from the known package prefix.
                String fullPath = p.toString().replace(File.separatorChar, '/');
                int idx = fullPath.indexOf(packagePath);
                if (idx < 0) {
                    continue;
                }
                String className = fullPath.substring(idx)
                        .replaceAll("\\.class$", "")
                        .replace('/', '.');
                if (!className.startsWith(packageName)) {
                    continue;
                }
                try {
                    Class<?> cls = cl.loadClass(className);
                    if (JsonDeserializer.class.isAssignableFrom(cls)
                            && cls != JsonDeserializer.class
                            && cls != JsonDeserializer.None.class) {
                        result.add(className);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // skip classes that cannot be loaded in the test JVM
                }
            }
        }
    }

    /**
     * Minimal POJO for deserializing reflect-config.json entries.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ReflectEntry {
        public String name;
    }
}
