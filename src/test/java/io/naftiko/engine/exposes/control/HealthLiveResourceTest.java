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
package io.naftiko.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;

/**
 * Unit tests for {@link HealthLiveResource}.
 */
public class HealthLiveResourceTest {

    @Test
    public void getLiveShouldReturnUp() throws Exception {
        HealthLiveResource resource = new HealthLiveResource();

        Representation result = resource.getLive();

        assertNotNull(result);
        assertEquals(MediaType.APPLICATION_JSON, result.getMediaType());
        assertTrue(result.getText().contains("\"status\":\"UP\""));
    }
}
