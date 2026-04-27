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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import io.naftiko.spec.exposes.skill.ExposedSkillSpec;

/**
 * Handles {@code GET /skills/{name}/contents} — lists all files in the skill's {@code location}
 * directory.
 *
 * <p>Returns 404 if the skill is not found or has no {@code location} configured.</p>
 */
public class ContentsResource extends SkillServerResource {

    private static final Logger logger = LoggerFactory.getLogger(ContentsResource.class);

    @Get("json")
    public Representation listContents() throws Exception {
        String name = getAttribute("name");
        ExposedSkillSpec skill = findSkill(name);
        if (skill == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND, "Skill not found: " + name);
        }
        if (skill.getLocation() == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "No location configured for skill: " + name);
        }

        Path root = Paths.get(URI.create(skill.getLocation())).normalize().toAbsolutePath();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "Location directory not found for skill: " + name);
        }

        ArrayNode fileList = getMapper().createArrayNode();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                ObjectNode entry = getMapper().createObjectNode();
                entry.put("path", root.relativize(file).toString().replace('\\', '/'));
                try {
                    entry.put("size", Files.size(file));
                } catch (IOException e) {
                    logger.debug("Could not read size of file '{}': {}", file, e.getMessage(), e);
                    entry.put("size", 0);
                }
                entry.put("type", detectMediaType(file.getFileName().toString()).getName());
                fileList.add(entry);
            });
        }

        ObjectNode response = getMapper().createObjectNode();
        response.put("name", name);
        response.set("files", fileList);

        return new JacksonRepresentation<>(response);
    }
}
