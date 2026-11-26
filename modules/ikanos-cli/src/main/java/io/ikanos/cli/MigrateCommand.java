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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code ikanos migrate}: rewrites capability documents written before steps became a list.
 *
 * <p>See {@link StepsMigrator} for what changes. Files are rewritten in place; with
 * {@code --dry-run} they are only listed, and the command exits with {@code 1} when one needs a
 * change, so it can guard a build.</p>
 */
@Command(
    name = "migrate",
    mixinStandardHelpOptions = true,
    description = "Rewrite capability documents to the current format: 'steps' as a list of named"
            + " steps, and 'what' instead of 'call' in a call step. Comments and layout are kept."
)
public class MigrateCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "PATH",
            description = "Capability files, or directories searched for .yml, .yaml and .json"
                    + " files")
    private List<Path> paths;

    @Option(names = "--dry-run",
            description = "List the files that need a change without writing them; exit with 1"
                    + " when there is one")
    private boolean dryRun;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        List<Path> files = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                err.println("Not found: " + path);
                return 2;
            }
            try {
                files.addAll(documents(path));
            } catch (IOException e) {
                err.println("Cannot read " + path + ": " + e.getMessage());
                return 2;
            }
        }
        int changed = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String migrated = migrate(file, text);
                if (!migrated.equals(text)) {
                    changed++;
                    if (dryRun) {
                        out.println("Needs migration: " + file);
                    } else {
                        Files.writeString(file, migrated, StandardCharsets.UTF_8);
                        out.println("Migrated: " + file);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Keep going: one unreadable file should not leave the others unmigrated.
                err.println("Cannot migrate " + file + ": " + e.getMessage());
                failed++;
            }
        }
        if (changed == 0 && failed == 0) {
            out.println("Nothing to migrate in " + files.size() + " file(s).");
        }
        if (failed > 0) {
            return 2;
        }
        return dryRun && changed > 0 ? 1 : 0;
    }

    static String migrate(Path file, String text) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? StepsMigrator.migrateJson(text)
                : StepsMigrator.migrateYaml(text);
    }

    private static List<Path> documents(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .filter(MigrateCommand::isDocument)
                    .sorted()
                    .toList();
        }
    }

    private static boolean isDocument(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json");
    }
}
