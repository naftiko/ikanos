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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.spec.exposes.mcp.McpServerPromptSpec;
import io.ikanos.spec.exposes.mcp.McpPromptMessageSpec;

public class PromptHandlerTest {

    @Test
    public void constructorShouldTreatNullPromptListAsEmpty() {
        PromptHandler handler = new PromptHandler((Map<String, McpServerPromptSpec>) null);

        assertTrue(handler.listAll().isEmpty(), "Null prompt list should behave like no prompts");
    }

    @Test
    public void constructorShouldSkipPromptEntriesWithNullName() {
        // With named-object maps, keys cannot be null — a prompt with name=null cannot
        // be stored in the map. Verify that an empty map produces an empty handler.
        PromptHandler handler = new PromptHandler(Map.of());

        assertTrue(handler.listAll().isEmpty(),
                "Empty prompt map should produce empty handler");
    }

    @Test
    public void renderShouldThrowWhenPromptUnknown() {
        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("known-prompt");

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class,
                        () -> handler.render("unknown-prompt", Map.of()));
        assertTrue(error.getMessage().contains("Unknown prompt"));
    }

    @Test
    public void renderInlineShouldSubstituteArguments() throws IOException {
        McpPromptMessageSpec msg = new McpPromptMessageSpec();
        msg.setRole("user");
        msg.setContent("Hello {{name}}, you have {{count}} tasks");

        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("greeting");
        spec.getTemplate().add(msg);

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));
        List<PromptHandler.RenderedMessage> result =
                handler.render("greeting", Map.of("name", "Alice", "count", "5"));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).role);
        assertEquals("Hello Alice, you have 5 tasks", result.get(0).text);
    }

    @Test
    public void renderInlineShouldLeaveUnknownPlaceholdersUnchanged() throws IOException {
        McpPromptMessageSpec msg = new McpPromptMessageSpec();
        msg.setRole("user");
        msg.setContent("Hello {{name}}, you have {{unknown}} items");

        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("greeting");
        spec.getTemplate().add(msg);

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));
        List<PromptHandler.RenderedMessage> result =
                handler.render("greeting", Map.of("name", "Bob"));

        assertEquals("Hello Bob, you have {{unknown}} items", result.get(0).text);
    }

    @Test
    public void renderInlineShouldNotReinterpolateInjectedValues(@TempDir Path tempDir)
            throws IOException {
        McpPromptMessageSpec msg = new McpPromptMessageSpec();
        msg.setRole("user");
        msg.setContent("You said: {{message}}");

        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("echo");
        spec.getTemplate().add(msg);

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));
        // Argument value contains {{...}} which should NOT be re-interpolated
        List<PromptHandler.RenderedMessage> result =
                handler.render("echo", Map.of("message", "{{danger}}"));

        assertEquals("You said: {{danger}}", result.get(0).text);
    }

    @Test
    public void renderFileBasedShouldLoadAndSubstitute(@TempDir Path tempDir)
            throws IOException {
        Path promptFile = tempDir.resolve("prompt.txt");
        Files.writeString(promptFile, "Analyze {{topic}} for {{audience}}");

        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("analyze");
        spec.setLocation(promptFile.toUri().toString());

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));
        List<PromptHandler.RenderedMessage> result = handler.render("analyze",
                Map.of("topic", "solar energy", "audience", "engineers"));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).role);
        assertEquals("Analyze solar energy for engineers", result.get(0).text);
    }

    @Test
    public void renderFileBasedShouldThrowWhenFileNotFound() {
        McpServerPromptSpec spec = new McpServerPromptSpec();
        spec.setName("missing");
        spec.setLocation("file:///nonexistent/prompt.txt");

        PromptHandler handler = new PromptHandler(Map.of(spec.getName(), spec));

        IOException error = assertThrows(IOException.class,
                () -> handler.render("missing", Map.of()));
        assertTrue(error.getMessage().contains("not found") || error.getMessage().contains("can't find"));
    }

    @Test
    public void substituteShouldHandleMultiplePlaceholders() {
        String template = "Name: {{first}} {{last}}, Age: {{age}}";
        Map<String, String> args = Map.of("first", "John", "last", "Doe", "age", "30");

        String result = PromptHandler.substitute(template, args);

        assertEquals("Name: John Doe, Age: 30", result);
    }
}


