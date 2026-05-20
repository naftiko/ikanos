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

public enum FileFormat {
    YAML("Yaml","yaml"),
    UNKNOWN("Unknown","unknown");

    public final String display;
    public final String pathName;

    private FileFormat(String display, String pathName) {
        this.display = display;
        this.pathName = pathName;
    }

    public static FileFormat valueOfDisplay(String display) {
        for (FileFormat fileFormat : values()) {
            if (java.util.Objects.equals(fileFormat.display, display)) {
                return fileFormat;
            }
        }
        return FileFormat.UNKNOWN;
    }
}
