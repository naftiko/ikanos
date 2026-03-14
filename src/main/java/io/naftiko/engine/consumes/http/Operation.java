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
package io.naftiko.engine.consumes.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HTTP Operation representation
 */
public class Operation {
    
    private volatile String httpMethod;

    private volatile String targetUri;

    private List<Parameter> parameters;

    private final List<Response> responses;

    public Operation() {
        super();
        this.httpMethod = null;
        this.targetUri = null;  
        this.parameters = new CopyOnWriteArrayList<>();
        this.responses = new CopyOnWriteArrayList<>();
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getTargetUri() {
        return targetUri;
    }

    public List<Response> getResponses() {
        return this.responses;
    }

}
