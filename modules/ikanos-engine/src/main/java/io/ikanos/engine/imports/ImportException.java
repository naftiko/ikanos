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
package io.ikanos.engine.imports;

import java.io.IOException;

/**
 * Thrown when an import directive cannot be resolved.
 *
 * <p>Every message includes the section name so that error reports from the four sections
 * share a predictable, uniform shape.</p>
 */
public class ImportException extends IOException {

    private final String sectionName;

    public ImportException(String sectionName, String message) {
        super("[" + sectionName + "] " + message);
        this.sectionName = sectionName;
    }

    public ImportException(String sectionName, String message, Throwable cause) {
        super("[" + sectionName + "] " + message, cause);
        this.sectionName = sectionName;
    }

    public String getSectionName() {
        return sectionName;
    }
}
