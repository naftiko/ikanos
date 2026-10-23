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
import tools.jackson.databind.ValueDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.util.IkanosMappers;
import org.restlet.resource.ServerResource;

/**
 * Validates that the GraalVM native-image reflect-config.json contains all classes
 * required for Jackson serialization in the import openapi command, and the Restlet
 * server resources that the HTTP adapters instantiate by reflection.
 */
public class NativeImageReflectConfigTest {

    private static final String SPEC_PACKAGE = "io.ikanos.spec";
    private static final String ENGINE_PACKAGE = "io.ikanos.engine";

    private static Set<String> registeredClasses;
    private static Map<String, ReflectEntry> entriesByName;

    @BeforeAll
    static void loadReflectConfig() throws Exception {
        ObjectMapper mapper = IkanosMappers.json();
        try (InputStream is = NativeImageReflectConfigTest.class.getClassLoader()
                .getResourceAsStream("META-INF/native-image/reflect-config.json")) {
            assertNotNull(is, "reflect-config.json must be on the classpath");
            List<ReflectEntry> entries = mapper.readValue(is,
                    new TypeReference<List<ReflectEntry>>() {});
            registeredClasses = entries.stream()
                    .map(e -> e.name)
                    .collect(Collectors.toSet());
            entriesByName = entries.stream()
                    .collect(Collectors.toMap(e -> e.name, e -> e, (a, b) -> a));
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
     * Dynamically discovers every {@link ValueDeserializer} subclass in the
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
     * Restlet's {@code Finder} creates every routed {@link ServerResource} per request with
     * {@code getDeclaredConstructor().newInstance()}, and {@code AnnotationUtils} finds the
     * {@code @Get}/{@code @Post} handlers with {@code getDeclaredMethods()}. In a native image a
     * class missing from {@code reflect-config.json} makes {@code Finder} answer 404 on every
     * route, while the JVM works fine (#855). Abstract bases are included, since their declared
     * methods are scanned too.
     */
    @Test
    void reflectConfigShouldRegisterAllEngineServerResourcesForNativeMode() throws Exception {
        List<String> resourceClasses = findSubclasses(ENGINE_PACKAGE, ServerResource.class);
        assertFalse(resourceClasses.isEmpty(),
                "Classpath scan found no ServerResource subclasses in " + ENGINE_PACKAGE
                        + " — the scan logic may be broken");

        List<String> missing = resourceClasses.stream()
                .filter(c -> {
                    ReflectEntry e = entriesByName.get(c);
                    return e == null || !e.allDeclaredConstructors || !e.allDeclaredMethods;
                })
                .sorted()
                .toList();

        assertTrue(missing.isEmpty(),
                "reflect-config.json must register these ServerResource subclasses with "
                        + "allDeclaredConstructors and allDeclaredMethods, or the native binary "
                        + "answers 404 on their routes: " + missing);
    }

    /**
     * Scans the classpath for all classes in {@code packageName} (and sub-packages)
     * that are assignable to {@link ValueDeserializer}, using only the JDK class loader
     * and NIO — no third-party scanning library required.
     *
     * <p>Works for both exploded directories (IDE / {@code mvn test}) and JAR files
     * (shaded uber-jar or native-image build). The {@code JsonDeserializer.None} sentinel
     * inner class is excluded because it is not a real deserializer.</p>
     */
    static List<String> findJsonDeserializerSubclasses(String packageName) throws Exception {
        return findSubclasses(packageName, ValueDeserializer.class);
    }

    /**
     * Scans the classpath for all classes in {@code packageName} (and sub-packages) that are
     * assignable to {@code baseType}, excluding {@code baseType} itself and the
     * {@code ValueDeserializer.None} sentinel.
     */
    static List<String> findSubclasses(String packageName, Class<?> baseType) throws Exception {
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
                    collectSubclasses(root, packageName, baseType, cl, result);
                }
            } else {
                // exploded directory on the file system
                Path root = Path.of(uri);
                collectSubclasses(root, packageName, baseType, cl, result);
            }
        }
        return result;
    }

    private static void collectSubclasses(Path root, String packageName, Class<?> baseType,
            ClassLoader cl, List<String> result) throws Exception {
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
                    if (baseType.isAssignableFrom(cls)
                            && cls != baseType
                            && cls != ValueDeserializer.None.class) {
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
        public boolean allDeclaredConstructors;
        public boolean allDeclaredMethods;
    }
}
