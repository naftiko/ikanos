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
package io.ikanos.engine.consumes.http;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.VersionHelper;

import java.io.File;
import java.io.InputStream;

/**
 * Integration tests for Protocol Buffer (Protobuf) parsing capabilities. Tests the decoding of
 * Protobuf messages using the Capability framework with proto schemas.
 */
public class ProtobufIntegrationTest {

        private Capability capability;
        private String schemaVersion;

        @BeforeEach
        public void setUp() throws Exception {
                // Load the Protobuf capability from test resources
                String resourcePath = "src/test/resources/formats/proto-capability.yaml";
                File file = new File(resourcePath);

                assertTrue(file.exists(),
                                "ProtoBuf capability test file should exist at " + resourcePath);

                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

                // Initialize capability
                capability = new Capability(spec);
                schemaVersion = VersionHelper.getSchemaVersion();
        }

        @Test
        public void testCapabilityLoaded() {
                assertNotNull(capability, "Capability should be initialized");
                assertNotNull(capability.getSpec(), "Capability spec should be loaded");
                assertEquals(schemaVersion, capability.getSpec().getIkanos(),
                                "ikanos version should be " + schemaVersion);
        }

        @Test
        public void testCapabilityHasServerAdapters() {
                assertFalse(capability.getServerAdapters().isEmpty(),
                                "Capability should have at least one server adapter");
                assertEquals(1, capability.getServerAdapters().size(),
                                "ProtoBuf test capability should have exactly one server adapter");
        }

        @Test
        public void testCapabilityHasClientAdapters() {
                assertFalse(capability.getClientAdapters().isEmpty(),
                                "Capability should have at least one client adapter");
                assertEquals(1, capability.getClientAdapters().size(),
                                "ProtoBuf test capability should have exactly one client adapter");
        }

        @Test
        public void testProtoSchemaFileExists() {
                // Verify that the proto schema file is accessible
                InputStream protoStream = ProtobufIntegrationTest.class.getClassLoader()
                                .getResourceAsStream("schemas/test-records.proto");
                assertNotNull(protoStream, "Proto schema file should exist in test resources");
        }

        @Test
        public void testProtoSchemaLoading() throws Exception {
                // Verify that we can load and parse the proto schema
                InputStream protoStream = ProtobufIntegrationTest.class.getClassLoader()
                                .getResourceAsStream("schemas/test-records.proto");
                assertNotNull(protoStream, "Proto schema file should exist");

                ProtobufSchema schema = ProtobufSchemaLoader.std.load(protoStream);
                assertNotNull(schema, "Proto schema should be loaded");
        }

        @Test
        public void testCapabilityProtobufFormat() {
                // Verify the operation has Protobuf format and schema specified
                var consumes = capability.getSpec().getCapability().getConsumes();
                assertFalse(consumes.isEmpty(), "Capability should have consume specs");

                HttpClientSpec httpSpec = (HttpClientSpec) consumes.get(0);
                var resources = httpSpec.getResources();
                assertFalse(resources.isEmpty(), "Resource should exist");

                var operations = resources.get(0).getOperations();
                assertFalse(operations.isEmpty(), "Resource should have operations");

                var operation = operations.get(0);
                assertEquals("protobuf", operation.getOutputRawFormat(),
                                "Operation should specify Protobuf format");
        }

        @Test
        public void testProtobufMapperInitialization() throws Exception {
                // Verify that ProtobufMapper can be initialized
                InputStream protoStream = ProtobufIntegrationTest.class.getClassLoader()
                                .getResourceAsStream("schemas/test-records.proto");
                assertNotNull(protoStream, "Proto schema file should exist");

                ProtobufSchemaLoader.std.load(protoStream);
                ProtobufMapper mapper = new ProtobufMapper();
                assertNotNull(mapper, "ProtobufMapper should be initialized");
        }

        @Test
        public void testProtoSchemaStructure() throws Exception {
                // Verify the proto schema defines the expected structure
                InputStream protoStream = ProtobufIntegrationTest.class.getClassLoader()
                                .getResourceAsStream("schemas/test-records.proto");
                assertNotNull(protoStream, "Proto schema file should exist");

                // Read the raw proto content for verification
                String protoContent = new String(protoStream.readAllBytes());
                assertTrue(protoContent.contains("message RecordsList"),
                                "Proto should define RecordsList message");
                assertTrue(protoContent.contains("message Record"),
                                "Proto should define Record message");
                assertTrue(protoContent.contains("string id"), "Record should have id field");
                assertTrue(protoContent.contains("string title"), "Record should have title field");
                assertTrue(protoContent.contains("string description"),
                                "Record should have description field");
        }

        @Test
        public void testCapabilityProtobufIntegration() {
                // Comprehensive test of ProtoBuf capability setup
                var spec = capability.getSpec();
                assertNotNull(spec, "Spec should exist");

                var exposes = spec.getCapability().getExposes();
                assertEquals(1, exposes.size(), "Should have one expose server");
                RestServerSpec restServer = (RestServerSpec) exposes.get(0);
                assertEquals("test-protobuf", restServer.getNamespace(),
                                "Server namespace should be test-protobuf");

                var resources = restServer.getResources();
                assertEquals(1, resources.size(), "Should have one resource");
                assertEquals("/proto/records", resources.get(0).getPath(),
                                "Resource path should be /proto/records");

                var consumes = spec.getCapability().getConsumes();
                assertEquals(1, consumes.size(), "Should have one consume client");
                HttpClientSpec httpClient = (HttpClientSpec) consumes.get(0);
                assertEquals("mock-proto", httpClient.getNamespace(),
                                "Client namespace should be mock-proto");
        }
}
