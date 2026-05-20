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

/**
 * The three-field import directive, identical for every section.
 *
 * <p>Extracted from the {@code Imported*Spec} classes (which are Jackson-facing) into this
 * engine-facing record so the generic {@link ImportResolver} does not depend on any
 * section-specific spec class.</p>
 *
 * @param from            file path to a source file (relative or absolute)
 * @param importNamespace the {@code namespace} of the entry to take from the source file
 * @param alias           if non-null, replaces the imported entry's namespace in the capability
 * @param description     optional free-form documentation
 */
public record ImportDirective(
        String from,
        String importNamespace,
        String alias,
        String description
) {}
