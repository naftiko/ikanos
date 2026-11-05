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

import tools.jackson.core.JsonParser;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * Rejects YAML aliases in Ikanos documents.
 *
 * <p>Jackson does not resolve a YAML alias ({@code *name}): it reads it as a plain string, which
 * the spec model then drops without error, while {@code ikanos validate} rejects the same string
 * against the schema. Ikanos does not support anchors and aliases, so every reader of a document
 * calls {@link #reject(byte[])} first and fails with the alias and its location (#923).</p>
 */
public final class YamlAliases {

    private YamlAliases() {
    }

    /**
     * Fails on the first YAML alias in a document.
     *
     * @param yaml the document content
     * @return the same content, so that callers can pass it on to a mapper
     * @throws StreamReadException naming the alias and its line and column, if the document
     *         contains one, or if the document is not well-formed YAML
     */
    public static byte[] reject(byte[] yaml) {
        try (JsonParser parser = IkanosMappers.yaml().createParser(yaml)) {
            while (parser.nextToken() != null) {
                if (parser instanceof YAMLParser yamlParser && yamlParser.isCurrentAlias()) {
                    throw new StreamReadException(parser, "YAML alias '*" + parser.getString()
                            + "' is not supported: Ikanos does not resolve anchors and aliases,"
                            + " so repeat the anchored content instead",
                            parser.currentTokenLocation());
                }
            }
        }
        return yaml;
    }
}
