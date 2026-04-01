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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.naftiko.spec.OutputParameterSpec;

public class ResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Regression test for: nested object property without a top-level mapping (e.g. "specs") must
     * recurse into its own properties and resolve them against the same root, instead of returning
     * null.
     *
     * <p>Scenario: a "get-ship" output spec declares a "specs" property of type object with no
     * mapping key. Its children (yearBuilt, tonnage, length) each have their own JSONPath mappings
     * that point to flat fields on the API response ($.year_built, $.gross_tonnage,
     * $.dimensions.length_overall). Before the fix, "specs" was always resolved as NullNode
     * because the resolver tried to jsonPathExtract with a null mapping.</p>
     */
    @Test
    public void resolveOutputMappingsShouldRecurseIntoNestedObjectWithoutTopLevelMapping()
            throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                {
                  "imo_number": "IMO-9321483",
                  "vessel_name": "Northern Star",
                  "vessel_type": "cargo",
                  "flag_code": "NO",
                  "operational_status": "active",
                  "year_built": 2015,
                  "gross_tonnage": 42000,
                  "dimensions": {
                    "length_overall": 229
                  }
                }
                """);

        // Build the "specs" sub-spec (type object, no mapping, has properties)
        OutputParameterSpec yearBuilt = new OutputParameterSpec("yearBuilt", "number", null, "$.year_built");
        OutputParameterSpec tonnage = new OutputParameterSpec("tonnage", "number", null, "$.gross_tonnage");
        OutputParameterSpec length = new OutputParameterSpec("length", "number", null, "$.dimensions.length_overall");

        OutputParameterSpec specsSpec = new OutputParameterSpec();
        specsSpec.setName("specs");
        specsSpec.setType("object");
        specsSpec.getProperties().addAll(List.of(yearBuilt, tonnage, length));

        // Build the root object spec
        OutputParameterSpec imoSpec = new OutputParameterSpec("imo", "string", null, "$.imo_number");
        OutputParameterSpec nameSpec = new OutputParameterSpec("name", "string", null, "$.vessel_name");
        OutputParameterSpec statusSpec = new OutputParameterSpec("status", "string", null, "$.operational_status");

        OutputParameterSpec rootSpec = new OutputParameterSpec();
        rootSpec.setType("object");
        rootSpec.getProperties().addAll(List.of(imoSpec, nameSpec, statusSpec, specsSpec));

        JsonNode result = Resolver.resolveOutputMappings(rootSpec, apiResponse, MAPPER);

        assertNotNull(result);
        assertTrue(result.isObject());
        assertEquals("IMO-9321483", result.path("imo").asText());
        assertEquals("Northern Star", result.path("name").asText());
        assertEquals("active", result.path("status").asText());

        JsonNode specs = result.path("specs");
        assertFalse(specs.isMissingNode(), "specs must be present");
        assertFalse(specs.isNull(), "specs must not be null");
        assertTrue(specs.isObject(), "specs must be an object");
        assertEquals(2015, specs.path("yearBuilt").asInt());
        assertEquals(42000, specs.path("tonnage").asInt());
        assertEquals(229, specs.path("length").asInt());
    }
}
