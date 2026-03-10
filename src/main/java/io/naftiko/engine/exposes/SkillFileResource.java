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

import java.nio.file.Files;
import java.nio.file.Path;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.FileRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import io.naftiko.spec.exposes.ExposedSkillSpec;

/**
 * Handles {@code GET /skills/{name}/contents/{file}} — serves an individual file from the skill's
 * {@code location} directory.
 *
 * <p>The {@code {file}} template variable is configured with {@code TYPE_URI_PATH} in
 * {@link SkillServerAdapter}, allowing multi-segment paths such as
 * {@code examples/advanced-usage.md}.</p>
 *
 * <p>Path traversal is blocked via {@link SkillServerResource#resolveAndValidate}.</p>
 *
 * <ul>
 *   <li>Returns 400 if the requested path contains unsafe segments.</li>
 *   <li>Returns 404 if the skill, location, or file is not found.</li>
 * </ul>
 */
public class SkillFileResource extends SkillServerResource {

    @Get
    public Representation getFile() {
        String name = getAttribute("name");
        String file = getAttribute("file");

        ExposedSkillSpec skill = findSkill(name);
        if (skill == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND, "Skill not found: " + name);
        }
        if (skill.getLocation() == null) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "No location configured for skill: " + name);
        }

        Path resolved;
        try {
            resolved = resolveAndValidate(skill.getLocation(), file);
        } catch (SecurityException e) {
            throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
        }

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND,
                    "File not found: " + file);
        }

        MediaType mediaType = detectMediaType(resolved.getFileName().toString());
        return new FileRepresentation(resolved.toFile(), mediaType);
    }
}
