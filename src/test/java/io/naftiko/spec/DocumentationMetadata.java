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
package io.naftiko.spec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.naftiko.spec.exposes.ApiServerOperationSpec;
import io.naftiko.spec.exposes.ApiServerResourceSpec;
import io.naftiko.spec.exposes.ApiServerStepSpec;
import io.naftiko.spec.exposes.OperationStepSpec;
import io.naftiko.spec.exposes.OperationStepCallSpec;

/**
 * Manages documentation metadata for capability specifications.
 * Extracts and provides access to descriptions from various spec elements.
 */
public class DocumentationMetadata {

    /**
     * Extracts documentation for a resource including its description and operations.
     * 
     * @param resource The resource specification
     * @return Map containing resource documentation
     */
    public static Map<String, Object> extractResourceDocumentation(ApiServerResourceSpec resource) {
        Map<String, Object> docs = new HashMap<>();
        
        if (resource != null) {
            docs.put("path", resource.getPath());
            docs.put("description", resource.getDescription());
            
            // Extract operation descriptions
            Map<String, String> operations = new HashMap<>();
            List<ApiServerOperationSpec> ops = resource.getOperations();
            if (ops != null) {
                for (ApiServerOperationSpec op : ops) {
                    if (op != null && op.getName() != null) {
                        operations.put(op.getName(), op.getDescription() != null ? op.getDescription() : "");
                    }
                }
            }
            docs.put("operations", operations);
        }
        
        return docs;
    }

    /**
     * Extracts parameter documentation for an operation.
     * 
     * @param inputParams List of input parameters
     * @param outputParams List of output parameters
     * @return Map containing parameter documentation
     */
    public static Map<String, Object> extractParameterDocumentation(
            List<InputParameterSpec> inputParams,
            List<OutputParameterSpec> outputParams) {
        Map<String, Object> docs = new HashMap<>();
        
        // Document input parameters
        Map<String, String> inputs = new HashMap<>();
        if (inputParams != null) {
            for (InputParameterSpec param : inputParams) {
                if (param != null && param.getName() != null) {
                    String desc = param.getDescription() != null ? param.getDescription() : "";
                    String type = param.getType() != null ? param.getType() : "unknown";
                    inputs.put(param.getName(), String.format("%s (%s)", desc, type));
                }
            }
        }
        docs.put("inputs", inputs);
        
        // Document output parameters
        Map<String, String> outputs = new HashMap<>();
        if (outputParams != null) {
            for (OutputParameterSpec param : outputParams) {
                if (param != null && param.getName() != null) {
                    String desc = param.getDescription() != null ? param.getDescription() : "";
                    String type = param.getType() != null ? param.getType() : "unknown";
                    outputs.put(param.getName(), String.format("%s (%s)", desc, type));
                }
            }
        }
        docs.put("outputs", outputs);
        
        return docs;
    }

    /**
     * Extracts step documentation including descriptions of each step.
     * 
     * @param steps List of operation steps
     * @return List of step documentation with descriptions
     */
    public static List<Map<String, Object>> extractStepDocumentation(List<ApiServerStepSpec> steps) {
        List<Map<String, Object>> stepDocs = new java.util.ArrayList<>();
        
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                ApiServerStepSpec step = steps.get(i);
                if (step != null) {
                    Map<String, Object> stepDoc = new HashMap<>();
                    stepDoc.put("index", i);
                    stepDoc.put("description", step.getDescription() != null ? step.getDescription() : "");
                    
                    if (step.getCall() != null) {
                        stepDoc.put("operation", step.getCall().getOperation());
                        if (step.getCall().getDescription() != null) {
                            stepDoc.put("callDescription", step.getCall().getDescription());
                        }
                    }
                    
                    stepDocs.add(stepDoc);
                }
            }
        }
        
        return stepDocs;
    }

    /**
     * Extracts step documentation from new OperationStepSpec hierarchy.
     * 
     * @param steps List of operation steps
     * @return List of step documentation with descriptions
     */
    public static List<Map<String, Object>> extractStepDocumentationFromOperationSteps(List<OperationStepSpec> steps) {
        List<Map<String, Object>> stepDocs = new java.util.ArrayList<>();
        
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                OperationStepSpec step = steps.get(i);
                if (step != null) {
                    Map<String, Object> stepDoc = new HashMap<>();
                    stepDoc.put("index", i);
                    stepDoc.put("name", step.getName() != null ? step.getName() : "");
                    
                    if (step instanceof OperationStepCallSpec) {
                        OperationStepCallSpec callStep = (OperationStepCallSpec) step;
                        if (callStep.getCall() != null) {
                            stepDoc.put("operation", callStep.getCall());
                        }
                    }
                    
                    stepDocs.add(stepDoc);
                }
            }
        }
        
        return stepDocs;
    }

    /**
     * Creates a human-readable summary of operation documentation.
     * 
     * @param resource The resource specification
     * @param operation The operation specification
     * @return Formatted documentation string
     */
    public static String formatOperationDocumentation(ApiServerResourceSpec resource,
            ApiServerOperationSpec operation) {
        StringBuilder doc = new StringBuilder();
        
        if (resource != null && operation != null) {
            doc.append("Resource: ").append(resource.getPath()).append("\n");
            if (resource.getDescription() != null && !resource.getDescription().isEmpty()) {
                doc.append("Description: ").append(resource.getDescription()).append("\n");
            }
            
            doc.append("\nOperation: ").append(operation.getName()).append("\n");
            if (operation.getDescription() != null && !operation.getDescription().isEmpty()) {
                doc.append("Description: ").append(operation.getDescription()).append("\n");
            }
            
            if (operation.getSteps() != null && !operation.getSteps().isEmpty()) {
                doc.append("\nSteps:\n");
                for (int i = 0; i < operation.getSteps().size(); i++) {
                    OperationStepSpec step = operation.getSteps().get(i);
                    doc.append("  ").append(i + 1).append(". ");
                    if (step.getName() != null && !step.getName().isEmpty()) {
                        doc.append(step.getName());
                    } else if (step instanceof OperationStepCallSpec) {
                        OperationStepCallSpec callStep = (OperationStepCallSpec) step;
                        if (callStep.getCall() != null) {
                            doc.append("Call: ").append(callStep.getCall());
                        } else {
                            doc.append("(No description)");
                        }
                    } else {
                        doc.append("(No description)");
                    }
                    doc.append("\n");
                }
            }
        }
        
        return doc.toString();
    }

}
