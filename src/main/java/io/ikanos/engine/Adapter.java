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
package io.ikanos.engine;

/**
 * Abstract Adapter class defining the basic lifecycle methods
 */
public abstract class Adapter {

    public abstract void start() throws Exception;

    public abstract void stop() throws Exception;
        
    /**
     * Converts a Mustache template to a URI template.
     *
     * @param mustacheTemplate the Mustache template
     * @return the URI template
     */
    public static String toUriTemplate(String mustacheTemplate) {
        return mustacheTemplate.replaceAll("\\{\\{(\\w+)\\}\\}", "{$1}");
    }

}
