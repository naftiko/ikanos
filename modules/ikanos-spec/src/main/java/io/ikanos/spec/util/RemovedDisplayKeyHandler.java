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
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;

/**
 * Fails the load of a spec element that still sets {@code display}, which was renamed to
 * {@code title} (#1164).
 *
 * <p>Capability documents are read leniently, so without this handler a {@code display} key would
 * be silently dropped and the element would lose its title. Only spec classes are checked, so a
 * {@code display} key in user data (a {@code with} value, a mock body) is left alone, since those
 * are read as maps, not as spec beans.</p>
 */
final class RemovedDisplayKeyHandler extends DeserializationProblemHandler {

    static final String MESSAGE = "`display` is no longer supported: it was renamed to `title` "
            + "(#1164). Rename the `display` key to `title`.";

    private static final String SPEC_PACKAGE = "io.ikanos.spec.";

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
            ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) {
        if (!"display".equals(propertyName) || beanOrClass == null) {
            return false;
        }
        Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
        if (!type.getName().startsWith(SPEC_PACKAGE)) {
            return false;
        }
        ctxt.reportInputMismatch(type, MESSAGE);
        return true;
    }

}
