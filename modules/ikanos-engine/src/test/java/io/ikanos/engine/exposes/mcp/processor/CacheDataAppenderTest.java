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
package io.ikanos.engine.exposes.mcp.processor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.exception.ProcessorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.ikanos.spec.util.IkanosMappers;

class CacheDataAppenderTest {

    ObjectMapper mapper = IkanosMappers.json();
    DispatchPostProcessor processor = new CacheDataAppender(300, "public");

    @Test
    void applyShouldKeepHintsTheHandlerAlreadySet() throws ProcessorException {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("result").put("ttlMs", 5000).put("cacheScope", "private");

        processor.apply(response);

        assertThat(response.path("result").path("ttlMs").asInt()).isEqualTo(5000);
        assertThat(response.path("result").path("cacheScope").asString()).isEqualTo("private");
    }

    @Test
    void applyShouldAddCacheData() throws ProcessorException {
        // Given
        ObjectNode response = mapper.createObjectNode();
        response.set("result", mapper.createObjectNode());

        // When
        processor.apply(response);

        // Then
        assertThat(response.path("result").path("ttlMs").asInt()).isEqualTo(300);
        assertThat(response.path("result").path("cacheScope").asString()).isEqualTo("public");
    }

    @Test
    void constructorShouldRejectANegativeTtl() {
        // The engine does not validate the schema at load time, so the guard lives here too
        assertThatThrownBy(() -> new CacheDataAppender(-1, "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlMs");
    }
}