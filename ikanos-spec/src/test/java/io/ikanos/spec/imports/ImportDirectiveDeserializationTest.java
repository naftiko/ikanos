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
package io.ikanos.spec.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.aggregates.ImportedAggregateSpec;
import io.ikanos.spec.aggregates.InlineAggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.consumes.http.ImportedConsumesHttpSpec;
import io.ikanos.spec.exposes.ImportedExposesSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.BindingSpec;
import io.ikanos.spec.util.ImportedBindingSpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parametrized-style tests for the unified import directive ({@code from}/{@code import}/
 * {@code as}/{@code description}) across the four sections: {@code consumes}, {@code exposes},
 * {@code aggregates}, {@code binds}.
 *
 * <p>Each section has one happy-path test (with alias and description), one bare-minimum test
 * (no alias, no description), and one inline-coexistence test verifying that mixing inline and
 * imported entries in the same array works.</p>
 *
 * <p>See {@code blueprints/unified-import-mechanism.md} §5 and §16 (Phase 1).</p>
 */
class ImportDirectiveDeserializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    // ----- consumes -----

    @Test
    @DisplayName("consumes: import with alias and description deserializes into ImportedConsumesHttpSpec")
    void consumesImportShouldDeserializeIntoImportedConsumesHttpSpec() throws Exception {
        String yaml = """
            from: "./shared/notion.consumes.yml"
            import: "notion"
            as: "notion-shared"
            description: "Standard Notion adapter."
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        ImportedConsumesHttpSpec imported = assertInstanceOf(ImportedConsumesHttpSpec.class, result);
        assertEquals("./shared/notion.consumes.yml", imported.getFrom());
        assertEquals("notion", imported.getImportNamespace());
        assertEquals("notion-shared", imported.getAlias());
        assertEquals("Standard Notion adapter.", imported.getDescription());
        assertEquals("notion-shared", imported.getNamespace());
    }

    @Test
    @DisplayName("consumes: import without alias falls back to the source namespace")
    void consumesImportWithoutAliasShouldUseSourceNamespace() throws Exception {
        String yaml = """
            from: "./shared.yml"
            import: "shared-api"
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        ImportedConsumesHttpSpec imported = assertInstanceOf(ImportedConsumesHttpSpec.class, result);
        assertNull(imported.getAlias());
        assertEquals("shared-api", imported.getNamespace());
    }

    @Test
    @DisplayName("consumes: inline HttpClientSpec without 'from' is still parsed as HttpClientSpec")
    void consumesInlineWithoutFromShouldDeserializeAsHttpClientSpec() throws Exception {
        String yaml = """
            namespace: "api"
            baseUri: "https://api.example.com"
            resources: []
            """;

        ClientSpec result = mapper.readValue(yaml, ClientSpec.class);

        assertInstanceOf(HttpClientSpec.class, result);
    }

    // ----- exposes -----

    @Test
    @DisplayName("exposes: import with alias and description deserializes into ImportedExposesSpec")
    void exposesImportShouldDeserializeIntoImportedExposesSpec() throws Exception {
        String yaml = """
            from: "./shared/maritime.exposes.yml"
            import: "maritime-rest"
            as: "fleet-maritime-rest"
            description: "Shared maritime REST surface."
            """;

        ServerSpec result = mapper.readValue(yaml, ServerSpec.class);

        ImportedExposesSpec imported = assertInstanceOf(ImportedExposesSpec.class, result);
        assertEquals("./shared/maritime.exposes.yml", imported.getFrom());
        assertEquals("maritime-rest", imported.getImportNamespace());
        assertEquals("fleet-maritime-rest", imported.getAlias());
        assertEquals("Shared maritime REST surface.", imported.getDescription());
        assertEquals("fleet-maritime-rest", imported.getEffectiveNamespace());
    }

    @Test
    @DisplayName("exposes: inline rest adapter without 'from' is still parsed via 'type' discriminant")
    void exposesInlineWithoutFromShouldDispatchOnTypeDiscriminant() throws Exception {
        String yaml = """
            type: "rest"
            namespace: "shipyard-rest"
            port: 3003
            """;

        ServerSpec result = mapper.readValue(yaml, ServerSpec.class);

        assertInstanceOf(RestServerSpec.class, result);
    }

    // ----- aggregates -----

    @Test
    @DisplayName("aggregates: import with alias and description deserializes into ImportedAggregateSpec")
    void aggregatesImportShouldDeserializeIntoImportedAggregateSpec() throws Exception {
        String yaml = """
            from: "./shared/maritime-aggregates.yml"
            import: "crew-resolver"
            as: "fleet-crew-resolver"
            description: "Crew-resolver domain functions."
            """;

        AggregateSpec result = mapper.readValue(yaml, AggregateSpec.class);

        ImportedAggregateSpec imported = assertInstanceOf(ImportedAggregateSpec.class, result);
        assertEquals("./shared/maritime-aggregates.yml", imported.getFrom());
        assertEquals("crew-resolver", imported.getImportNamespace());
        assertEquals("fleet-crew-resolver", imported.getAlias());
        assertEquals("Crew-resolver domain functions.", imported.getDescription());
        assertEquals("fleet-crew-resolver", imported.getEffectiveNamespace());
    }

    @Test
    @DisplayName("aggregates: inline entry without 'from' deserializes into base AggregateSpec")
    void aggregatesInlineWithoutFromShouldDeserializeIntoAggregateSpec() throws Exception {
        String yaml = """
            display: "Crew Resolver"
            namespace: "crew-resolver"
            flows: {}
            """;

        AggregateSpec result = mapper.readValue(yaml, AggregateSpec.class);

        assertInstanceOf(InlineAggregateSpec.class, result);
        assertEquals("crew-resolver", result.getNamespace());
    }

    // ----- binds -----

    @Test
    @DisplayName("binds: import with alias and description deserializes into ImportedBindingSpec")
    void bindsImportShouldDeserializeIntoImportedBindingSpec() throws Exception {
        String yaml = """
            from: "./shared/prod-secrets.binds.yml"
            import: "secrets"
            as: "prod-secrets"
            description: "Shared production secret catalog."
            """;

        BindingSpec result = mapper.readValue(yaml, BindingSpec.class);

        ImportedBindingSpec imported = assertInstanceOf(ImportedBindingSpec.class, result);
        assertEquals("./shared/prod-secrets.binds.yml", imported.getFrom());
        assertEquals("secrets", imported.getImportNamespace());
        assertEquals("prod-secrets", imported.getAlias());
        assertEquals("Shared production secret catalog.", imported.getDescription());
        assertEquals("prod-secrets", imported.getEffectiveNamespace());
    }

    @Test
    @DisplayName("binds: inline entry without 'from' keeps the runtime 'location' semantics")
    void bindsInlineWithoutFromShouldKeepLocationAsRuntimeUri() throws Exception {
        String yaml = """
            namespace: "secrets"
            location: "file:///.env"
            keys:
              API_KEY: "api-key"
            """;

        BindingSpec result = mapper.readValue(yaml, BindingSpec.class);

        assertEquals("secrets", result.getNamespace());
        // The 'location' here is the runtime variable-source URI, NOT an import directive.
        assertEquals("file:///.env", result.getLocation());
    }

    // ----- coexistence in a single document -----

    @Test
    @DisplayName("a single capability document mixes inline and imported entries in all four sections")
    void documentShouldAllowInlineAndImportedEntriesInTheSameSection() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            info:
              display: "mixed"
              description: "Capability mixing inline and imported entries."
            capability:
              binds:
                - namespace: "local-secrets"
                  location: "file:///.env"
                  keys:
                    API_KEY: "api-key"
                - from: "./shared/prod-secrets.binds.yml"
                  import: "secrets"
                  as: "prod-secrets"
              consumes:
                - type: "http"
                  namespace: "inline-api"
                  baseUri: "https://api.example.com"
                - from: "./shared/notion.consumes.yml"
                  import: "notion"
              exposes:
                - type: "rest"
                  namespace: "inline-rest"
                  port: 3003
                - from: "./shared/maritime.exposes.yml"
                  import: "maritime-rest"
              aggregates:
                crew-resolver:
                  display: "Crew Resolver"
                  flows: {}
                fleet-aggregates:
                  from: "./shared/maritime-aggregates.yml"
                  import: "fleet-aggregates"
            """;

        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);

        // binds: 1 inline + 1 imported
        assertEquals(2, spec.getCapability().getBinds().size());
        assertInstanceOf(ImportedBindingSpec.class, spec.getCapability().getBinds().get(1));

        // consumes: 1 inline + 1 imported
        assertEquals(2, spec.getCapability().getConsumes().size());
        assertInstanceOf(HttpClientSpec.class, spec.getCapability().getConsumes().get(0));
        assertInstanceOf(ImportedConsumesHttpSpec.class, spec.getCapability().getConsumes().get(1));

        // exposes: 1 inline + 1 imported
        assertEquals(2, spec.getCapability().getExposes().size());
        assertInstanceOf(RestServerSpec.class, spec.getCapability().getExposes().get(0));
        assertInstanceOf(ImportedExposesSpec.class, spec.getCapability().getExposes().get(1));

        // aggregates: 1 inline + 1 imported (capability.aggregates is a named-object map keyed by namespace)
        assertEquals(2, spec.getCapability().getAggregates().size());
        assertInstanceOf(ImportedAggregateSpec.class, spec.getCapability().getAggregates().get("fleet-aggregates"));
    }

    // ----- standalone document shapes -----

    @Test
    @DisplayName("standalone-exposes document parses with top-level 'exposes' array")
    void standaloneExposesDocumentShouldParseAtRoot() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            exposes:
              - type: "rest"
                namespace: "shared-rest"
                port: 3003
            """;

        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);

        assertEquals(1, spec.getExposes().size());
        assertInstanceOf(RestServerSpec.class, spec.getExposes().get(0));
    }

    @Test
    @DisplayName("standalone-aggregates document parses with top-level 'aggregates' array")
    void standaloneAggregatesDocumentShouldParseAtRoot() throws Exception {
        String yaml = """
            ikanos: "1.0.0-alpha3"
            aggregates:
              - display: "Crew Resolver"
                namespace: "crew-resolver"
                flows: {}
            """;

        IkanosSpec spec = mapper.readValue(yaml, IkanosSpec.class);

        assertEquals(1, spec.getAggregates().size());
        assertEquals("crew-resolver", spec.getAggregates().get(0).getNamespace());
    }

    // ----- error cases -----

    @Test
    @DisplayName("consumes: legacy 'location' keyword on an import entry is rejected with migration guidance")
    void consumesLegacyLocationShouldBeRejectedWithMigrationMessage() {
        String yaml = """
            location: "./api.yml"
            import: "myapi"
            """;

        JsonMappingException ex = assertThrows(
            JsonMappingException.class,
            () -> mapper.readValue(yaml, ClientSpec.class)
        );
        String message = ex.getMessage();
        assertTrue(
            message.contains("'location'") && message.contains("'from'"),
            "Expected migration guidance naming both 'location' and 'from', but was: " + message
        );
    }
}
