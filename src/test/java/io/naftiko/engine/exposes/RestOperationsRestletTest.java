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
package io.naftiko.engine.exposes;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.naftiko.engine.Resolver;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for RestOperationsRestlet, particularly the Mustache template resolution functionality.
 */
public class RestOperationsRestletTest {

    /**
     * Test that null template returns null.
     */
    @Test
    public void testResolveMustacheTemplateWithNullTemplate() throws Exception {
        String result = invokeMustacheResolver(null, new HashMap<>());
        assertNull(result, "Null template should return null");
    }

    /**
     * Test that template with null parameters returns the original template.
     */
    @Test
    public void testResolveMustacheTemplateWithNullParameters() throws Exception {
        String template = "/api/users/{{userId}}/posts";
        String result = invokeMustacheResolver(template, null);
        assertEquals(template, result, "Template with null parameters should remain unchanged");
    }

    /**
     * Test that template with empty parameters returns the original template.
     */
    @Test
    public void testResolveMustacheTemplateWithEmptyParameters() throws Exception {
        String template = "/api/users/{{userId}}/posts";
        String result = invokeMustacheResolver(template, new HashMap<>());
        assertEquals(template, result, "Template with empty parameters should remain unchanged");
    }

    /**
     * Test resolving a single parameter in a template.
     */
    @Test
    public void testResolveMustacheTemplateSingleParameter() throws Exception {
        String template = "/databases/{{database_id}}/pages";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("database_id", "2fe4adce-3d02-8028-bec8-000bfb5cafa2");

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/databases/2fe4adce-3d02-8028-bec8-000bfb5cafa2/pages", result,
                "Should resolve database_id parameter");
    }

    /**
     * Test resolving multiple parameters in a template.
     */
    @Test
    public void testResolveMustacheTemplateMultipleParameters() throws Exception {
        String template = "/api/{{version}}/users/{{userId}}/posts/{{postId}}";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("version", "v1");
        parameters.put("userId", "user123");
        parameters.put("postId", "post456");

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/api/v1/users/user123/posts/post456", result,
                "Should resolve all three parameters");
    }

    /**
     * Test that parameters with null values are replaced with empty strings.
     */
    @Test
    public void testResolveMustacheTemplateParameterWithNullValue() throws Exception {
        String template = "/api/{{prefix}}/resource/{{suffix}}";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("prefix", "v1");
        parameters.put("suffix", null);

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/api/v1/resource/", result,
                "Should replace null parameter values with empty string");
    }

    /**
     * Test that unresolved placeholders remain in the template.
     */
    @Test
    public void testResolveMustacheTemplateWithUnresolvedPlaceholders() throws Exception {
        String template = "/api/{{version}}/users/{{userId}}/posts/{{postId}}";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("version", "v1");
        // userId and postId not provided

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/api/v1/users//posts/", result,
                "Should leave unresolved placeholders intact");
    }

    /**
     * Test resolving numeric parameter values.
     */
    @Test
    public void testResolveMustacheTemplateNumericParameter() throws Exception {
        String template = "/databases/db{{id}}/tables/table{{tableNum}}";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", 42);
        parameters.put("tableNum", 7);

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/databases/db42/tables/table7", result,
                "Should convert numeric parameters to strings");
    }

    /**
     * Test that the same parameter can appear multiple times in the template.
     */
    @Test
    public void testResolveMustacheTemplateRepeatedParameter() throws Exception {
        String template = "/users/{{userId}}/profile/{{userId}}/settings";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", "abc123");

        String result = invokeMustacheResolver(template, parameters);
        assertEquals("/users/abc123/profile/abc123/settings", result,
                "Should resolve repeated parameter placeholders");
    }

    /**
     * Helper method to invoke the resolveMustacheTemplate method from the Resolver class.
     */
    private String invokeMustacheResolver(String template, Map<String, Object> parameters)
            throws Exception {
        return Resolver.resolveMustacheTemplate(template, parameters);
    }
}
