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
package io.ikanos.spec.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

class IkanosMappersTest {

    /** Bean whose collection is final and has no setter, as in the spec model. */
    static class Holder {
        private final List<String> tags = new ArrayList<>();
        private String zeta;
        private String alpha;

        public List<String> getTags() {
            return tags;
        }

        public String getZeta() {
            return zeta;
        }

        public void setZeta(String zeta) {
            this.zeta = zeta;
        }

        public String getAlpha() {
            return alpha;
        }

        public void setAlpha(String alpha) {
            this.alpha = alpha;
        }
    }

    @Test
    void jsonShouldKeepJackson2Defaults() {
        ObjectMapper mapper = IkanosMappers.json();

        assertTrue(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
        assertFalse(mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        assertTrue(mapper.isEnabled(MapperFeature.USE_GETTERS_AS_SETTERS));
    }

    @Test
    void jsonShouldPopulateFinalCollectionThroughGetter() {
        Holder holder = IkanosMappers.json().readValue("{\"tags\":[\"a\",\"b\"]}", Holder.class);

        assertEquals(List.of("a", "b"), holder.getTags());
    }

    @Test
    void jsonShouldKeepDeclarationOrderWhenSerializing() {
        Holder holder = new Holder();
        holder.setZeta("z");
        holder.setAlpha("a");

        String json = IkanosMappers.json().writeValueAsString(holder);

        assertTrue(json.indexOf("\"zeta\"") < json.indexOf("\"alpha\""), json);
    }

    @Test
    void jsonShouldRejectUnknownProperties() {
        ObjectMapper mapper = IkanosMappers.json();

        assertThrows(UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"unknown\":1}", Holder.class));
    }

    @Test
    void yamlShouldRejectUnknownProperties() {
        ObjectMapper mapper = IkanosMappers.yaml();

        assertThrows(UnrecognizedPropertyException.class,
                () -> mapper.readValue("unknown: 1\n", Holder.class));
    }

    @Test
    void lenientMappersShouldIgnoreUnknownProperties() {
        Holder fromYaml = IkanosMappers.lenientYaml().readValue("alpha: a\nunknown: 1\n",
                Holder.class);
        Holder fromJson = IkanosMappers.lenientJson().readValue("{\"alpha\":\"a\",\"unknown\":1}",
                Holder.class);

        assertEquals("a", fromYaml.getAlpha());
        assertEquals("a", fromJson.getAlpha());
    }
}
