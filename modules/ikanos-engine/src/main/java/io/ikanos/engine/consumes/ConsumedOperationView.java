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
package io.ikanos.engine.consumes;

import java.util.List;
import io.ikanos.engine.util.BinarySize;
import io.ikanos.spec.OperationSpec;
import io.ikanos.spec.OutputParameterSpec;

/**
 * Read-only, protocol-neutral view of a consumed operation's output declarations.
 *
 * <p>Exposes only what the engine needs after a call has executed: output mappings, the raw
 * format and schema used for conversion to JSON, and the binary-content settings. It deliberately
 * omits HTTP concepts such as the method or the parent resource, which have no meaning for an MCP
 * tool or a SQL procedure.</p>
 */
public interface ConsumedOperationView {

    /** @return the operation name as declared under {@code consumes} */
    String name();

    /** @return the declared output parameters; never {@code null} */
    List<OutputParameterSpec> outputParameters();

    /** @return the declared {@code outputRawFormat}, or {@code null} for JSON */
    String outputRawFormat();

    /** @return the declared {@code outputSchema}, or {@code null} */
    String outputSchema();

    /** @return the declared {@code outputMediaType}, or {@code null} */
    String outputMediaType();

    /** @return the per-operation {@code maxBinarySize} sized string, or {@code null} */
    String maxBinarySize();

    /**
     * Whether the operation declares {@code outputRawFormat: binary}. Detection is declarative,
     * never heuristic.
     *
     * @return {@code true} iff {@link #outputRawFormat()} equals {@code "binary"} ignoring case
     */
    default boolean isBinary() {
        return "binary".equalsIgnoreCase(outputRawFormat());
    }

    /**
     * Resolve the effective binary size cap in bytes: the per-operation {@code maxBinarySize} wins
     * over the exposing adapter's cap, which wins over the engine default.
     *
     * @param adapterMaxBinarySize the exposing adapter's sized string, or {@code null}
     * @return the cap in bytes
     * @throws IllegalArgumentException if a declared size string is malformed
     */
    default long maxBinaryBytes(String adapterMaxBinarySize) {
        String opSize = maxBinarySize();
        if (opSize != null && !opSize.isBlank()) {
            return BinarySize.parse(opSize);
        }
        return BinarySize.parseOrDefault(adapterMaxBinarySize);
    }

    /** A view with no declarations, used when no operation is bound. */
    ConsumedOperationView NONE = new ConsumedOperationView() {
        @Override
        public String name() {
            return null;
        }

        @Override
        public List<OutputParameterSpec> outputParameters() {
            return List.of();
        }

        @Override
        public String outputRawFormat() {
            return null;
        }

        @Override
        public String outputSchema() {
            return null;
        }

        @Override
        public String outputMediaType() {
            return null;
        }

        @Override
        public String maxBinarySize() {
            return null;
        }
    };

    /**
     * Build a view over a generic {@link OperationSpec}. {@code outputMediaType} and
     * {@code maxBinarySize} are not part of the generic spec, so they are supplied separately.
     *
     * @param op              the operation spec; {@code null} yields {@link #NONE}
     * @param outputMediaType the declared output media type, or {@code null}
     * @param maxBinarySize   the declared per-operation cap, or {@code null}
     * @return the view
     */
    static ConsumedOperationView of(OperationSpec op, String outputMediaType,
            String maxBinarySize) {
        if (op == null) {
            return NONE;
        }
        return new ConsumedOperationView() {
            @Override
            public String name() {
                return op.getName();
            }

            @Override
            public List<OutputParameterSpec> outputParameters() {
                List<OutputParameterSpec> params = op.getOutputParameters();
                return params != null ? params : List.of();
            }

            @Override
            public String outputRawFormat() {
                return op.getOutputRawFormat();
            }

            @Override
            public String outputSchema() {
                return op.getOutputSchema();
            }

            @Override
            public String outputMediaType() {
                return outputMediaType;
            }

            @Override
            public String maxBinarySize() {
                return maxBinarySize;
            }
        };
    }
}
