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

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * The value the engine puts under {@link Resolver#CALLER_ROOT} in template parameters: the
 * caller's validated identity, built by the exposed adapters from the OAuth 2.1 token (#998).
 *
 * <p>A map, so templates read it as {@code {{auth.claims.sub}}}, and a type of its own, so
 * {@link Resolver#requireCallerValues} can tell it from anything else that ended up under the same
 * name, such as a step named {@code auth} or a {@code with} key: a template only reads caller
 * values from this type.</p>
 */
public final class CallerRoot extends AbstractMap<String, Object> {

    private final Map<String, Object> content;

    /** @param content the root's entries, such as {@code claims}; copied, never changed */
    public CallerRoot(Map<String, Object> content) {
        this.content = Map.copyOf(content);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return content.entrySet();
    }

    @Override
    public Object get(Object key) {
        return content.get(key);
    }
}
