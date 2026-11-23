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
package io.ikanos.client.graphql;

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

/** A capability that consumes GraphQL, loaded through the engine. */
class GraphqlCapabilityTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private Capability load(String hints) throws Exception {
        Files.copy(Path.of(getClass().getClassLoader().getResource("tracker.graphql").toURI()),
                dir.resolve("tracker.graphql"));
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: graphql
                      namespace: tracker
                      endpoint: "http://localhost:9/graphql"
                      schema: ./tracker.graphql
                      operations:
                        save-issue:
                          description: Create an issue.
                          document: |
                            mutation ($input: IssueCreateInput!) { issueCreate(input: $input) { issue { id } } }
                          inputParameters:
                            title: { type: string, required: true, description: Title., wireName: input.title }
                            team-id: { type: string, required: true, description: Team., wireName: input.teamId }
                  exposes:
                    - type: mcp
                      namespace: tracker-mcp
                      port: 3001
                      %s
                      tools:
                        save-issue:
                          description: Create an issue.
                          call: tracker.save-issue
                          inputParameters:
                            title: { type: string, required: true, description: Title. }
                            team-id: { type: string, required: true, description: Team. }
                """.formatted(IKANOS, hints);
        IkanosSpec spec = IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec, dir.toString());
    }

    @Test
    void capabilityShouldLoadTheGraphqlAdapterFromItsModule() throws Exception {
        Capability capability = load("");

        assertInstanceOf(GraphqlConsumedAdapter.class, capability.getClientAdapters().get(0),
                "found through ServiceLoader; the schema read from the capability directory");
    }

    @Test
    void capabilityShouldRefuseAReadOnlyToolThatReachesAMutation() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("hints: { readOnly: true }"));

        assertTrue(e.getMessage().contains("tracker.save-issue"), e.getMessage());
    }
}
