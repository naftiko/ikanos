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
package io.ikanos.spec.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.IkanosMetaSchemaFactory;
import io.ikanos.spec.util.VersionHelper;

/**
 * Dispatch of exposed adapters by {@code type}, and the failure reported for the removed
 * {@code skill} adapter (#898).
 */
class ServerSpecDeserializerTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();
    private static final YAMLMapper YAML_MAPPER = IkanosMappers.yaml();

    private static final String SKILL_CAPABILITY = """
            ikanos: "%s"
            capability:
              exposes:
                - type: "skill"
                  port: 3002
                  namespace: "shipyard-skills"
                  skills:
                    fleet-ops:
                      description: "Fleet management"
            """.formatted(IKANOS);

    @Test
    void skillAdapterShouldFailWithTheMigrationMessage() {
        DatabindException error = assertThrows(DatabindException.class,
                () -> YAML_MAPPER.readValue(SKILL_CAPABILITY, IkanosSpec.class));

        assertTrue(error.getMessage().startsWith(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED),
                error.getMessage());
        assertTrue(error.getMessage().contains("`skills` block on a `type: mcp` adapter"));
        assertTrue(error.getMessage().contains("https://github.com/naftiko/ikanos/issues/898"));
    }

    @Test
    void skillAdapterShouldFailInAStandaloneExposesFile() {
        String yaml = """
                ikanos: "%s"
                exposes:
                  - type: "skill"
                    port: 3002
                    namespace: "shared-skills"
                """.formatted(IKANOS);

        DatabindException error = assertThrows(DatabindException.class,
                () -> YAML_MAPPER.readValue(yaml, IkanosSpec.class));

        assertTrue(error.getMessage().startsWith(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED),
                error.getMessage());
    }

    @Test
    void lenientMapperShouldAlsoRejectTheSkillAdapter() {
        DatabindException error = assertThrows(DatabindException.class,
                () -> IkanosMappers.lenientYaml().readValue(SKILL_CAPABILITY, IkanosSpec.class));

        assertTrue(error.getMessage().startsWith(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED),
                error.getMessage());
    }

    @Test
    void unknownTypeShouldStillBeReportedAsUnknown() {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "graphql"
                      port: 3000
                """.formatted(IKANOS);

        DatabindException error = assertThrows(DatabindException.class,
                () -> YAML_MAPPER.readValue(yaml, IkanosSpec.class));

        assertTrue(error.getMessage().contains("Unknown server spec type: 'graphql'"),
                error.getMessage());
    }

    @Test
    void mcpAdapterWithSkillsShouldDeserialize() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "mcp"
                      port: 3001
                      namespace: "shipyard-tools"
                      skills:
                        fleet-ops:
                          description: "Fleet management"
                """.formatted(IKANOS);

        IkanosSpec spec = YAML_MAPPER.readValue(yaml, IkanosSpec.class);

        McpServerSpec mcp = assertInstanceOf(McpServerSpec.class,
                spec.getCapability().getExposes().get(0));
        assertTrue(mcp.getSkills().containsKey("fleet-ops"));
    }

    @Test
    void schemaShouldRejectTheSkillAdapter() throws Exception {
        InputStream schemaStream = ServerSpecDeserializerTest.class.getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json");
        JsonNode schemaNode = IkanosMappers.json().readTree(schemaStream);
        Schema schema = IkanosMetaSchemaFactory.getInstance(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);

        assertFalse(schema.validate(YAML_MAPPER.readTree(SKILL_CAPABILITY)).isEmpty());
    }

    @Test
    void migrationMessageShouldNameTheEngineVersion() {
        assertTrue(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED.contains("Ikanos engine 1.0.0-beta9"),
                ServerSpecDeserializer.SKILL_ADAPTER_REMOVED);
    }

    @Test
    void skillAdapterRemovedRuleShouldCarryTheSameMessage() throws Exception {
        InputStream rulesStream = ServerSpecDeserializerTest.class.getClassLoader()
                .getResourceAsStream("rules/ikanos-rules.yml");
        JsonNode rule = YAML_MAPPER.readTree(rulesStream).path("rules")
                .path("ikanos-skill-adapter-removed");

        assertEquals(ServerSpecDeserializer.SKILL_ADAPTER_REMOVED, rule.path("message").asString(null));
        assertEquals("error", rule.path("severity").asString(null));
    }
}
