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
package io.ikanos.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class FileFormatEnumTest {

    @Test
    public void valueOfLabelShouldReturnYamlForYamlLabel() {
        FileFormat result = FileFormat.valueOfLabel("Yaml");

        assertEquals(FileFormat.YAML, result);
        assertEquals("yaml", result.pathName);
    }

    @Test
    public void valueOfLabelShouldReturnUnknownForUnrecognizedLabel() {
        FileFormat result = FileFormat.valueOfLabel("Unknown");

        assertEquals(FileFormat.UNKNOWN, result);
    }

    @Test
    public void valueOfLabelShouldReturnUnknownForNull() {
        FileFormat result = FileFormat.valueOfLabel(null);

        assertEquals(FileFormat.UNKNOWN, result);
    }

    @Test
    public void enumValuesShouldHaveCorrectLabels() {
        assertEquals("Yaml", FileFormat.YAML.label);
        assertEquals("Unknown", FileFormat.UNKNOWN.label);
    }

    @Test
    public void enumValuesShouldHaveCorrectPathNames() {
        assertEquals("yaml", FileFormat.YAML.pathName);
        assertEquals("unknown", FileFormat.UNKNOWN.pathName);
    }
}
