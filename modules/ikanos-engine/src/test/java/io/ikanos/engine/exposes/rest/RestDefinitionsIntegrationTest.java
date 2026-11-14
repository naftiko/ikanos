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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.openapi.OasExportBuilder;
import io.ikanos.spec.openapi.OasExportResult;
import io.ikanos.spec.openapi.OasYamlWriter;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.YamlAliases;
import io.swagger.v3.oas.models.SpecVersion;

/**
 * Loads capabilities whose REST adapter shares inputs and shapes through {@code definitions}
 * (#1000) and checks that the contract the adapter serves, as exported to OpenAPI, is the one of
 * the inline equivalent: same parameters, same response schemas, no reference left.
 */
class RestDefinitionsIntegrationTest {

    private static final Path TUTORIAL_DIR =
            Paths.get("src", "test", "resources", "tutorial").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void tutorialStep11OpenApiShouldBeByteForByteItsInlineVersion() throws Exception {
        String migrated = openApi(loadTutorial("step-11-shipyard-fleet-manifest.yml"),
                "migrated.json");
        String inline = openApi(loadTutorial("step-11-shipyard-fleet-manifest-inline.yml"),
                "inline.json");

        assertEquals(inline, migrated);
    }

    @Test
    void tutorialStep11OpenApiShouldCarryNoReference() throws Exception {
        String openApi = openApi(loadTutorial("step-11-shipyard-fleet-manifest.yml"),
                "migrated.json");

        assertFalse(openApi.contains("inputParameterSets"), openApi);
        assertFalse(openApi.contains("crew-member"), openApi);
        assertTrue(openApi.contains("\"fullName\""), "The crew items keep their shape");
    }

    @Test
    void capabilityShouldFailToLoadWhenARestOperationUsesAnUnknownSet() throws Exception {
        String yaml = tutorialYaml("step-11-shipyard-fleet-manifest.yml")
                .replaceFirst("inputParameterSets: \\[status-filter\\]",
                        "inputParameterSets: [status-filtre]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(yaml));

        assertTrue(error.getMessage().contains("REST adapter 'shipyard-api', resource"
                + " 'ships', operation 'list-ships': unknown input parameter set"
                + " 'status-filtre'"), error.getMessage());
    }

    /** Loads a capability the way the engine does, definitions and refs resolved. */
    private static IkanosSpec load(String yaml) throws Exception {
        IkanosSpec spec = IkanosMappers.yaml().readValue(
                YamlAliases.reject(yaml.getBytes(StandardCharsets.UTF_8)), IkanosSpec.class);
        new Capability(spec, TUTORIAL_DIR.toString());
        return spec;
    }

    private static IkanosSpec loadTutorial(String name) throws Exception {
        return load(tutorialYaml(name));
    }

    /**
     * A tutorial capability with its shared files (bindings, imports, skills) resolved from the
     * tutorial test directory, as the tutorial integration tests do.
     */
    private static String tutorialYaml(String name) throws Exception {
        String shared = TUTORIAL_DIR.resolve("shared").toUri().toString();
        return Files.readString(TUTORIAL_DIR.resolve(name)).replace("file:///./shared/", shared);
    }

    /** The OpenAPI document of the tutorial's REST adapter, as {@code ikanos export} writes it. */
    private String openApi(IkanosSpec spec, String file) throws Exception {
        OasExportResult result = new OasExportBuilder().build(spec, "shipyard-api",
                SpecVersion.V30);
        Path path = tempDir.resolve(file);
        new OasYamlWriter().writeJson(result.getOpenApi(), path);
        return Files.readString(path);
    }
}
