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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class CreateCapabilityCommandTest {

    @Test
    void callShouldFailWhenCapabilityNameIsEmpty() {
        CreateCapabilityCommand command = new CreateCapabilityCommand();
        command.input = new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        command.err = new PrintStream(err, true, StandardCharsets.UTF_8);

        int exitCode = command.call();

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("capability name cannot be empty"));
    }

    @Test
    void callShouldFailWhenBaseUriIsEmpty() {
        CreateCapabilityCommand command = new CreateCapabilityCommand();
        command.input = new ByteArrayInputStream("demo\n\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        command.err = new PrintStream(err, true, StandardCharsets.UTF_8);

        int exitCode = command.call();

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("targetUri cannot be empty"));
    }

    @Test
    void callShouldFailWhenPortIsEmpty() {
        CreateCapabilityCommand command = new CreateCapabilityCommand();
        command.input = new ByteArrayInputStream("demo\nhttps://api.example.com\n\n"
                .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        command.err = new PrintStream(err, true, StandardCharsets.UTF_8);

        int exitCode = command.call();

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("port cannot be empty"));
    }

    @Test
    void callShouldGenerateCapabilityFileWhenInputIsValid() {
        AtomicReference<String> captured = new AtomicReference<>();
        CreateCapabilityCommand command = new CreateCapabilityCommand() {
            @Override
            void generateCapabilityFile(String capabilityName, String baseUri, String port)
                    throws IOException {
                captured.set(capabilityName + "|" + baseUri + "|" + port);
            }
        };
        command.input = new ByteArrayInputStream("demo\nhttps://api.example.com\n8080\n"
                .getBytes(StandardCharsets.UTF_8));

        int exitCode = command.call();

        assertEquals(0, exitCode);
        assertEquals("demo|https://api.example.com|8080", captured.get());
    }

    @Test
    void callShouldFailWhenGenerationThrowsIOException() {
        CreateCapabilityCommand command = new CreateCapabilityCommand() {
            @Override
            void generateCapabilityFile(String capabilityName, String baseUri, String port)
                    throws IOException {
                throw new IOException("disk full");
            }
        };
        command.input = new ByteArrayInputStream("demo\nhttps://api.example.com\n8080\n"
                .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        command.err = new PrintStream(err, true, StandardCharsets.UTF_8);

        int exitCode = command.call();

        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("disk full"));
    }
}
