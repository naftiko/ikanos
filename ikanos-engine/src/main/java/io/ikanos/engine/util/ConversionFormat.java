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
package io.ikanos.engine.util;

/**
 * Centralized enum for the output raw formats declared in the Ikanos specification
 * ({@code outputRawFormat} in {@code ikanos-schema.json}).
 *
 * <p>Labels are lowercase to match the JSON schema enum values. Use {@link #fromLabel(String)}
 * for case-insensitive lookup from user-supplied strings.</p>
 */
public enum ConversionFormat {

    JSON("json"),
    XML("xml"),
    YAML("yaml"),
    CSV("csv"),
    TSV("tsv"),
    PSV("psv"),
    HTML("html"),
    MARKDOWN("markdown"),
    PROTOBUF("protobuf"),
    AVRO("avro");

    public final String label;

    ConversionFormat(String label) {
        this.label = label;
    }

    /**
     * Resolve a format string to a {@link ConversionFormat} constant (case-insensitive).
     *
     * @param format the raw format string (may be {@code null})
     * @return the matching constant, or {@code null} if {@code format} is {@code null} or unknown
     */
    public static ConversionFormat fromLabel(String format) {
        if (format == null) {
            return null;
        }
        for (ConversionFormat f : values()) {
            if (f.label.equalsIgnoreCase(format)) {
                return f;
            }
        }
        return null;
    }
}
