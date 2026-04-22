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
package io.naftiko.engine.util;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SafePathResolver.
 */
public class SafePathResolverTest {

    private static String testLocationUri;

    @BeforeAll
    static void resolveTestLocation() {
        File dir = new File("src/test/resources/scripts");
        testLocationUri = dir.toURI().toString();
        if (!testLocationUri.startsWith("file:///")) {
            testLocationUri = testLocationUri.replace("file:/", "file:///");
        }
    }

    @Test
    void resolveAndValidateShouldResolveSimpleFile() {
        Path result = SafePathResolver.resolveAndValidate(testLocationUri, "filter-active.js");
        assertNotNull(result);
        assertTrue(result.toString().endsWith("filter-active.js"));
    }

    @Test
    void resolveAndValidateShouldResolveNestedFile() {
        Path result = SafePathResolver.resolveAndValidate(testLocationUri, "lib/array-utils.js");
        assertNotNull(result);
        assertTrue(result.toString().endsWith("array-utils.js"));
    }

    @Test
    void resolveAndValidateShouldRejectPathTraversal() {
        assertThrows(SecurityException.class, () ->
                SafePathResolver.resolveAndValidate(testLocationUri, "../../etc/passwd"));
    }

    @Test
    void resolveAndValidateShouldRejectUnsafeSegments() {
        assertThrows(SecurityException.class, () ->
                SafePathResolver.resolveAndValidate(testLocationUri, "dir with spaces/file.js"));
    }

    @Test
    void resolveAndValidateShouldRejectDotDotSegments() {
        assertThrows(SecurityException.class, () ->
                SafePathResolver.resolveAndValidate(testLocationUri, "../secrets.txt"));
    }

    @Test
    void resolveAndValidateShouldHandleNonExistentRootGracefully() {
        String nonExistentUri = "file:///tmp/naftiko-nonexistent-dir-" + System.nanoTime();
        Path result = SafePathResolver.resolveAndValidate(nonExistentUri, "some-file.js");
        assertNotNull(result);
        assertTrue(result.toString().endsWith("some-file.js"));
    }

    @Test
    void resolveAndValidateShouldStillRejectTraversalWhenRootMissing() {
        String nonExistentUri = "file:///tmp/naftiko-nonexistent-dir-" + System.nanoTime();
        assertThrows(SecurityException.class, () ->
                SafePathResolver.resolveAndValidate(nonExistentUri, "../../etc/passwd"));
    }

}
