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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

public class MigrateCommandTest {

    private static final String STEP_MAP = """
            steps:
              fetch:
                type: call
                call: api.list-items
            """;

    private static final String STEP_LIST = """
            steps:
              - name: fetch
                type: call
                what: api.list-items
            """;

    @TempDir
    Path tempDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cli = new CommandLine(new MigrateCommand());
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    @Test
    public void callShouldRewriteEveryCapabilityFileOfADirectory() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path first = Files.writeString(tempDir.resolve("a.yml"), STEP_MAP);
        Path second = Files.writeString(nested.resolve("b.yaml"), STEP_MAP);
        Path other = Files.writeString(tempDir.resolve("notes.txt"), STEP_MAP);

        int exitCode = run(tempDir.toString());

        assertEquals(0, exitCode, err.toString());
        assertEquals(STEP_LIST, Files.readString(first));
        assertEquals(STEP_LIST, Files.readString(second));
        assertEquals(STEP_MAP, Files.readString(other));
    }

    @Test
    public void callWithDryRunShouldListTheFileWithoutWritingItAndExitWithOne() throws Exception {
        Path file = Files.writeString(tempDir.resolve("a.yml"), STEP_MAP);

        int exitCode = run("--dry-run", file.toString());

        assertEquals(1, exitCode);
        assertTrue(out.toString().contains("Needs migration: " + file), out.toString());
        assertEquals(STEP_MAP, Files.readString(file));
    }

    @Test
    public void callShouldExitWithZeroWhenNothingNeedsAChange() throws Exception {
        Path file = Files.writeString(tempDir.resolve("a.yml"), STEP_LIST);

        int exitCode = run("--dry-run", file.toString());

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Nothing to migrate in 1 file(s)."), out.toString());
    }

    @Test
    public void callShouldSkipAJsonFileItCannotReadAndMigrateTheOthers() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("a.json"), "{ not json");
        Path file = Files.writeString(tempDir.resolve("b.yml"), STEP_MAP);

        int exitCode = run(tempDir.toString());

        assertEquals(0, exitCode, err.toString());
        assertTrue(err.toString().contains("Skipped " + broken), err.toString());
        assertEquals(STEP_LIST, Files.readString(file));
    }

    @Test
    public void callWithDryRunShouldSkipAYamlFileItCannotReadAndExitWithZero() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("a.yml"), "key: [unclosed\n");

        int exitCode = run("--dry-run", tempDir.toString());

        assertEquals(0, exitCode, err.toString());
        assertTrue(err.toString().contains("Skipped " + broken), err.toString());
        assertEquals("key: [unclosed\n", Files.readString(broken));
    }

    @Test
    public void callShouldReportAFlowStyleStepMapItCannotRewriteAndExitWithTwo()
            throws Exception {
        Path file = Files.writeString(tempDir.resolve("a.yml"),
                "steps: {fetch: {type: call, call: api.list-items}}\n");

        int exitCode = run(file.toString());

        assertEquals(2, exitCode);
        assertTrue(err.toString().contains("Cannot migrate " + file + " fully"), err.toString());
        assertTrue(err.toString().contains("$.steps"), err.toString());
    }

    @Test
    public void callShouldExitWithTwoWhenAPathDoesNotExist() {
        int exitCode = run(tempDir.resolve("missing.yml").toString());

        assertEquals(2, exitCode);
        assertTrue(err.toString().contains("Not found"), err.toString());
    }
}
