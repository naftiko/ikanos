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
package io.ikanos.engine.consumes.contract;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The union members the inputs of one operation write, checked when the capability loads: a
 * request may set only one member of a union (a protobuf {@code oneof}, a GraphQL {@code @oneOf}
 * input, a Smithy {@code union}), so two inputs that write different members of the same union,
 * at the same place in the request, are refused. Each adapter resolves its {@code wireName} paths
 * against its own contract and reports every union member a path goes through.
 */
public final class UnionChoices {

    private record Choice(String member, String input) {
    }

    private final String where;
    private final Map<String, Choice> chosen = new HashMap<>();

    /** @param where the operation, as load errors name it */
    public UnionChoices(String where) {
        this.where = where;
    }

    /**
     * Records that an input writes a member of a union.
     *
     * @param input the input's name
     * @param place the path, from the request root, of the value holding the union: for a
     *        protobuf {@code oneof}, the message that declares it
     * @param union the union's name: the {@code oneof}, or the union type
     * @param member the member the input writes
     * @throws IllegalArgumentException when another input writes another member of it
     */
    public void choose(String input, List<String> place, String union, String member) {
        String key = String.join(".", place) + "#" + union;
        Choice previous = chosen.putIfAbsent(key, new Choice(member, input));
        if (previous != null && !previous.member().equals(member)) {
            String at = place.isEmpty() ? "" : " at '" + String.join(".", place) + "'";
            throw new IllegalArgumentException(where + " inputs '" + previous.input() + "' and '"
                    + input + "' set different members of union '" + union + "'" + at + " ('"
                    + previous.member() + "' and '" + member
                    + "'): a request sets only one member of a union");
        }
    }
}
