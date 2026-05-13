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
package io.ikanos.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerStepSpec;
import io.ikanos.spec.util.OperationStepCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * Unit tests for {@link DocumentationMetadata}. Each method exposes several branches
 * (null collection, null element, null/empty name or description, polymorphic step types) —
 * one focused test per branch keeps the failure messages diagnostic.
 */
public class DocumentationMetadataTest {

    // ────────────────────────── extractResourceDocumentation ──────────────────────────

    @Test
    public void extractResourceDocumentationShouldReturnEmptyMapWhenResourceIsNull() {
        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(null);
        assertNotNull(docs);
        assertTrue(docs.isEmpty());
    }

    @Test
    public void extractResourceDocumentationShouldExposePathAndDescriptionWhenResourceIsPresent() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets",
                "petsResource", "Pets", "Pet management endpoints", null);

        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);

        assertEquals("/pets", docs.get("path"));
        assertEquals("Pet management endpoints", docs.get("description"));
        @SuppressWarnings("unchecked")
        Map<String, String> ops = (Map<String, String>) docs.get("operations");
        assertNotNull(ops);
        assertTrue(ops.isEmpty());
    }

    @Test
    public void extractResourceDocumentationShouldHandleNullOperationsList() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/items");
        resource.setOperations(null);

        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);

        @SuppressWarnings("unchecked")
        Map<String, String> ops = (Map<String, String>) docs.get("operations");
        assertNotNull(ops);
        assertTrue(ops.isEmpty());
    }

    @Test
    public void extractResourceDocumentationShouldIncludeOperationDescriptionsWhenPresent() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        op.setDescription("List all pets");
        resource.getOperations().put(op.getName(), op);

        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);

        @SuppressWarnings("unchecked")
        Map<String, String> ops = (Map<String, String>) docs.get("operations");
        assertEquals("List all pets", ops.get("listPets"));
    }

    @Test
    public void extractResourceDocumentationShouldUseEmptyStringWhenOperationDescriptionIsNull() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("getPet");
        // no description set
        resource.getOperations().put(op.getName(), op);

        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);

        @SuppressWarnings("unchecked")
        Map<String, String> ops = (Map<String, String>) docs.get("operations");
        assertEquals("", ops.get("getPet"));
    }

    @Test
    public void extractResourceDocumentationShouldSkipNullOperationsAndUnnamedOperations() {
        // Use a resource with one named operation; unnamed/null entries in the map are
        // handled by the key being null or the operation having no name.
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec unnamed = new RestServerOperationSpec();
        // no name set — key is the name but value has no name
        resource.getOperations().put("unnamed", unnamed);
        RestServerOperationSpec named = new RestServerOperationSpec();
        named.setName("ok");
        named.setDescription("kept");
        resource.getOperations().put("ok", named);

        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) docs.get("operations");
        assertEquals("kept", result.get("ok"));
    }

    // ────────────────────────── extractParameterDocumentation ─────────────────────────

    @Test
    public void extractParameterDocumentationShouldReturnEmptyMapsWhenBothListsAreNull() {
        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(null, null);

        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) docs.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, String> outputs = (Map<String, String>) docs.get("outputs");
        assertTrue(inputs.isEmpty());
        assertTrue(outputs.isEmpty());
    }

    @Test
    public void extractParameterDocumentationShouldFormatInputAndOutputDescriptionsWithType() {
        InputParameterSpec in = new InputParameterSpec();
        in.setName("petId");
        in.setType("string");
        in.setDescription("Pet identifier");

        OutputParameterSpec out = new OutputParameterSpec();
        out.setName("status");
        out.setType("integer");
        out.setDescription("HTTP status");

        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(
                Arrays.asList(in), Arrays.asList(out));

        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) docs.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, String> outputs = (Map<String, String>) docs.get("outputs");
        assertEquals("Pet identifier (string)", inputs.get("petId"));
        assertEquals("HTTP status (integer)", outputs.get("status"));
    }

    @Test
    public void extractParameterDocumentationShouldUseUnknownWhenTypeIsNull() {
        InputParameterSpec in = new InputParameterSpec();
        in.setName("opaque");
        // no type, no description

        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(
                Arrays.asList(in), null);

        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) docs.get("inputs");
        assertEquals(" (unknown)", inputs.get("opaque"));
    }

    @Test
    public void extractParameterDocumentationShouldSkipNullParametersAndUnnamedParameters() {
        List<InputParameterSpec> inputs = new ArrayList<>();
        inputs.add(null);
        inputs.add(new InputParameterSpec()); // no name
        InputParameterSpec named = new InputParameterSpec();
        named.setName("kept");
        named.setType("string");
        named.setDescription("desc");
        inputs.add(named);

        List<OutputParameterSpec> outputs = new ArrayList<>();
        outputs.add(null);
        outputs.add(new OutputParameterSpec()); // no name
        OutputParameterSpec namedOut = new OutputParameterSpec();
        namedOut.setName("kept-out");
        namedOut.setType("string");
        namedOut.setDescription("desc-out");
        outputs.add(namedOut);

        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(
                inputs, outputs);

        @SuppressWarnings("unchecked")
        Map<String, String> inputResult = (Map<String, String>) docs.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, String> outputResult = (Map<String, String>) docs.get("outputs");
        assertEquals(1, inputResult.size());
        assertEquals("desc (string)", inputResult.get("kept"));
        assertEquals(1, outputResult.size());
        assertEquals("desc-out (string)", outputResult.get("kept-out"));
    }

    // ───────────────────── extractStepDocumentation (legacy RestServerStepSpec) ───────

    @Test
    public void extractStepDocumentationShouldReturnEmptyListWhenStepsIsNull() {
        List<Map<String, Object>> docs = DocumentationMetadata.extractStepDocumentation(null);
        assertNotNull(docs);
        assertTrue(docs.isEmpty());
    }

    @Test
    public void extractStepDocumentationShouldIndexEachStepAndIncludeDescriptionAndCallMetadata() {
        RestServerStepSpec step = new RestServerStepSpec();
        step.setDescription("Validate input");
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("checkPet");
        call.setDescription("Check pet existence");
        step.setCall(call);

        List<Map<String, Object>> docs = DocumentationMetadata.extractStepDocumentation(
                Arrays.asList(step));

        assertEquals(1, docs.size());
        assertEquals(0, docs.get(0).get("index"));
        assertEquals("Validate input", docs.get(0).get("description"));
        assertEquals("checkPet", docs.get(0).get("operation"));
        assertEquals("Check pet existence", docs.get(0).get("callDescription"));
    }

    @Test
    public void extractStepDocumentationShouldDefaultMissingDescriptionToEmptyString() {
        RestServerStepSpec step = new RestServerStepSpec();
        // no description, no call

        List<Map<String, Object>> docs = DocumentationMetadata.extractStepDocumentation(
                Arrays.asList(step));

        assertEquals("", docs.get(0).get("description"));
        assertFalse(docs.get(0).containsKey("operation"));
        assertFalse(docs.get(0).containsKey("callDescription"));
    }

    @Test
    public void extractStepDocumentationShouldOmitCallDescriptionWhenCallDescriptionIsNull() {
        RestServerStepSpec step = new RestServerStepSpec();
        ServerCallSpec call = new ServerCallSpec();
        call.setOperation("op");
        // no description on the call
        step.setCall(call);

        List<Map<String, Object>> docs = DocumentationMetadata.extractStepDocumentation(
                Arrays.asList(step));

        assertEquals("op", docs.get(0).get("operation"));
        assertFalse(docs.get(0).containsKey("callDescription"));
    }

    @Test
    public void extractStepDocumentationShouldSkipNullSteps() {
        List<RestServerStepSpec> steps = new ArrayList<>();
        steps.add(null);
        RestServerStepSpec kept = new RestServerStepSpec();
        kept.setDescription("kept");
        steps.add(kept);

        List<Map<String, Object>> docs = DocumentationMetadata.extractStepDocumentation(steps);

        assertEquals(1, docs.size());
        assertEquals("kept", docs.get(0).get("description"));
    }

    // ──────────── extractStepDocumentationFromOperationSteps (new hierarchy) ──────────

    @Test
    public void extractStepDocumentationFromOperationStepsShouldReturnEmptyListWhenStepsIsNull() {
        List<Map<String, Object>> docs =
                DocumentationMetadata.extractStepDocumentationFromOperationSteps(null);
        assertNotNull(docs);
        assertTrue(docs.isEmpty());
    }

    @Test
    public void extractStepDocumentationFromOperationStepsShouldIncludeNameAndOperation() {
        OperationStepCallSpec step = new OperationStepCallSpec("validate", "checkPet");

        List<Map<String, Object>> docs = DocumentationMetadata
                .extractStepDocumentationFromOperationSteps(Arrays.asList(step));

        assertEquals(1, docs.size());
        assertEquals(0, docs.get(0).get("index"));
        assertEquals("validate", docs.get(0).get("name"));
        assertEquals("checkPet", docs.get(0).get("operation"));
    }

    @Test
    public void extractStepDocumentationFromOperationStepsShouldDefaultNullNameToEmptyString() {
        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setCall("checkPet");

        List<Map<String, Object>> docs = DocumentationMetadata
                .extractStepDocumentationFromOperationSteps(Arrays.asList(step));

        assertEquals("", docs.get(0).get("name"));
        assertEquals("checkPet", docs.get(0).get("operation"));
    }

    @Test
    public void extractStepDocumentationFromOperationStepsShouldOmitOperationWhenCallIsNull() {
        OperationStepCallSpec step = new OperationStepCallSpec();
        step.setName("named-but-no-call");
        // no call value

        List<Map<String, Object>> docs = DocumentationMetadata
                .extractStepDocumentationFromOperationSteps(Arrays.asList(step));

        assertEquals("named-but-no-call", docs.get(0).get("name"));
        assertFalse(docs.get(0).containsKey("operation"));
    }

    @Test
    public void extractStepDocumentationFromOperationStepsShouldSkipNullEntries() {
        List<OperationStepSpec> steps = new ArrayList<>();
        steps.add(null);
        OperationStepCallSpec kept = new OperationStepCallSpec("kept", "callKept");
        steps.add(kept);

        List<Map<String, Object>> docs = DocumentationMetadata
                .extractStepDocumentationFromOperationSteps(steps);

        assertEquals(1, docs.size());
        assertEquals("kept", docs.get(0).get("name"));
    }

    // ────────────────────────── formatOperationDocumentation ──────────────────────────

    @Test
    public void formatOperationDocumentationShouldReturnEmptyStringWhenResourceIsNull() {
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        assertEquals("", DocumentationMetadata.formatOperationDocumentation(null, op));
    }

    @Test
    public void formatOperationDocumentationShouldReturnEmptyStringWhenOperationIsNull() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        assertEquals("", DocumentationMetadata.formatOperationDocumentation(resource, null));
    }

    @Test
    public void formatOperationDocumentationShouldIncludeResourceAndOperationDescriptions() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets",
                "petsResource", "Pets", "Pet management", null);
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        op.setDescription("List all pets");

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("Resource: /pets"));
        assertTrue(text.contains("Description: Pet management"));
        assertTrue(text.contains("Operation: listPets"));
        assertTrue(text.contains("Description: List all pets"));
    }

    @Test
    public void formatOperationDocumentationShouldOmitDescriptionsWhenEmpty() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        resource.setDescription("");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        op.setDescription("");

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("Resource: /pets"));
        assertTrue(text.contains("Operation: listPets"));
        assertFalse(text.contains("Description:"));
    }

    @Test
    public void formatOperationDocumentationShouldOmitDescriptionsWhenNull() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        // description left null
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertFalse(text.contains("Description:"));
    }

    @Test
    public void formatOperationDocumentationShouldNumberStepsWithExplicitName() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        op.getSteps().put("validate", new OperationStepCallSpec("validate", "checkPet"));
        op.getSteps().put("fetch", new OperationStepCallSpec("fetch", "getPets"));

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("Steps:"));
        assertTrue(text.contains("1. validate"));
        assertTrue(text.contains("2. fetch"));
    }

    @Test
    public void formatOperationDocumentationShouldFallBackToCallWhenNameIsBlank() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        OperationStepCallSpec callOnly = new OperationStepCallSpec();
        callOnly.setCall("getPets");
        op.getSteps().put("callOnly", callOnly);
        OperationStepCallSpec emptyName = new OperationStepCallSpec();
        emptyName.setName("");
        emptyName.setCall("checkPet");
        op.getSteps().put("emptyName", emptyName);

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("1. Call: getPets"));
        assertTrue(text.contains("2. Call: checkPet"));
    }

    @Test
    public void formatOperationDocumentationShouldEmitNoDescriptionPlaceholderWhenCallStepHasNoCall() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        OperationStepCallSpec orphan = new OperationStepCallSpec();
        // no name, no call
        op.getSteps().put("orphan", orphan);

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("1. (No description)"));
    }

    @Test
    public void formatOperationDocumentationShouldEmitNoDescriptionForUnknownStepTypeWithoutName() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        // An OperationStepSpec subclass that is NOT OperationStepCallSpec, with no name.
        op.getSteps().put("anon", new OperationStepSpec() { });

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertTrue(text.contains("1. (No description)"));
    }

    @Test
    public void formatOperationDocumentationShouldOmitStepsBlockWhenStepsIsEmpty() {
        RestServerResourceSpec resource = new RestServerResourceSpec("/pets");
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setName("listPets");
        // no steps added

        String text = DocumentationMetadata.formatOperationDocumentation(resource, op);

        assertFalse(text.contains("Steps:"));
    }
}
