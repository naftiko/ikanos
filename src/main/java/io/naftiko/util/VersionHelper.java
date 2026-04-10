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
package io.naftiko.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionHelper {
    private static final String SCHEMA_VERSION;

    static {
        try (InputStream versionStream = VersionHelper.class.getClassLoader().getResourceAsStream("version.properties")) {
            Properties props = new Properties();
            props.load(versionStream);
            String v = props.getProperty("version");
            SCHEMA_VERSION = v.endsWith("-SNAPSHOT") ? v.substring(0, v.length() - "-SNAPSHOT".length()) : v;
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Could not load version.properties: " + e.getMessage());
        }
    }

    public static String getSchemaVersion() {
        return SCHEMA_VERSION;
    }
}
