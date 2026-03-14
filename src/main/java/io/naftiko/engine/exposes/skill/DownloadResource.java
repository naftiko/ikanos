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
package io.naftiko.engine.exposes.skill;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.OutputRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import io.naftiko.spec.exposes.ExposedSkillSpec;

/**
 * Handles {@code GET /skills/{name}/download} — streams a ZIP archive of the skill's
 * {@code location} directory.
 *
 * <p>Returns 404 if the skill is not found, has no {@code location} configured, or the location
 * directory does not exist.</p>
 */
public class DownloadResource extends SkillServerResource {

    @Get
    public Representation download() {
        String name = getAttribute("name");
        ExposedSkillSpec skill = findSkill(name);
        if (skill == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND, "Skill not found: " + name);
        }
        if (skill.getLocation() == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "No location configured for skill: " + name);
        }

        final Path root = Paths.get(URI.create(skill.getLocation())).normalize().toAbsolutePath();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "Location directory not found for skill: " + name);
        }

        return new OutputRepresentation(MediaType.APPLICATION_ZIP) {
            @Override
            public void write(OutputStream os) throws IOException {
                try (ZipOutputStream zip = new ZipOutputStream(os)) {
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            String entry = root.relativize(file).toString().replace('\\', '/');
                            zip.putNextEntry(new ZipEntry(entry));
                            Files.copy(file, zip);
                            zip.closeEntry();
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        };
    }
}
