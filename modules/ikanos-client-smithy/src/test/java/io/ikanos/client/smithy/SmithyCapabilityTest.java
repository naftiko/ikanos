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
package io.ikanos.client.smithy;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/** A capability that consumes a Smithy-described service, loaded through the engine. */
class SmithyCapabilityTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private Capability load(String hints) throws Exception {
        Files.createDirectories(dir.resolve("contracts"));
        Files.copy(Path.of(getClass().getClassLoader().getResource("tracker.smithy").toURI()),
                dir.resolve("contracts/tracker.smithy"));
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: smithy
                      namespace: tracker
                      baseUri: "http://localhost:9"
                      model: ./contracts/tracker.smithy
                      service: "example.tracker#Tracker"
                      operations:
                        create-issue:
                          description: Create an issue.
                          operation: CreateIssue
                          inputParameters:
                            project-id: { type: string, required: true, description: Project., wireName: projectId }
                            title: { type: string, required: true, description: Title. }
                  exposes:
                    - type: mcp
                      namespace: tracker-mcp
                      port: 3001
                      %s
                      tools:
                        create-issue:
                          description: Create an issue.
                          call: tracker.create-issue
                          inputParameters:
                            project-id: { type: string, required: true, description: Project. }
                            title: { type: string, required: true, description: Title. }
                """.formatted(IKANOS, hints);
        IkanosSpec spec = IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec, dir.toString());
    }

    @Test
    void capabilityShouldLoadTheSmithyAdapterFromItsModule() throws Exception {
        Capability capability = load("");

        assertInstanceOf(SmithyConsumedAdapter.class, capability.getClientAdapters().get(0),
                "found through ServiceLoader; the model read from the capability directory");
    }

    @Test
    void capabilityShouldRefuseAReadOnlyToolThatReachesAWrite() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("hints: { readOnly: true }"));

        assertTrue(e.getMessage().contains("tracker.create-issue"), e.getMessage());
    }
}
