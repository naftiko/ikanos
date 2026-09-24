/*
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
package io.ikanos.spec.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

class SchemaValidatorFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(JsonMetaSchema.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @EnumSource(value = VersionFlag.class, names = {"V7", "V201909", "V202012"})
    void validateShouldRecognizeNameWithoutChangingConstraints(VersionFlag version) throws Exception {
        String uri = JsonSchemaFactory.checkVersion(version).getInstance().getUri();
        JsonSchema schema = SchemaValidatorFactory.getInstance(version).getSchema(mapper.readTree("""
            {
              "$schema": "%s",
              "name": "Example",
              "type": "object",
              "properties": {"value": {"name": "Value", "type": "string"}},
              "required": ["value"],
              "additionalProperties": false
            }
            """.formatted(uri)));

        assertTrue(schema.validate(mapper.readTree("{\"value\":\"ok\"}")).isEmpty());
        assertTrue(schema.validate(mapper.readTree("{}")).stream()
                .anyMatch(error -> error.getType().equals("required")));
        assertTrue(schema.validate(mapper.readTree("{\"value\":42}")).stream()
                .anyMatch(error -> error.getType().equals("type")));
        assertTrue(schema.validate(mapper.readTree("{\"value\":\"ok\",\"name\":\"extra\"}")).stream()
                .anyMatch(error -> error.getType().equals("additionalProperties")));
        assertTrue(logs.list.isEmpty(), () -> "Unexpected schema diagnostics: " + logs.list);
    }

    @ParameterizedTest
    @EnumSource(value = VersionFlag.class, names = {"V7", "V201909", "V202012"})
    void validateShouldStillWarnWhenAnotherKeywordIsUnknown(VersionFlag version) throws Exception {
        String keyword = "unknownKeywordFor" + version;
        SchemaValidatorFactory.getInstance(version).getSchema(mapper.readTree(
                "{\"%s\":true}".formatted(keyword))).validate(mapper.readTree("{}"));

        assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage()
                .contains("Unknown keyword " + keyword)));
    }
}
