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
package io.ikanos.engine.exposes.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.exposes.skill.ExposedSkillSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.skill.SkillServerSpec;
import io.ikanos.spec.exposes.skill.SkillToolSpec;

/**
 * Skill Server Adapter — exposes a read-only catalog of agent skills over predefined HTTP
 * endpoints.
 *
 * <p>Routes:</p>
 * <ul>
 *   <li>{@code GET /skills} — list all skills</li>
 *   <li>{@code GET /skills/{name}} — skill metadata + tool catalog</li>
 *   <li>{@code GET /skills/{name}/download} — ZIP archive</li>
 *   <li>{@code GET /skills/{name}/contents} — file listing</li>
 *   <li>{@code GET /skills/{name}/contents/{file}} — individual file</li>
 * </ul>
 *
 * <p>At construction time the adapter validates that every tool in every skill either references a
 * sibling {@code rest}/{@code mcp} namespace (via {@code from}) or points to an instruction file
 * (via {@code instruction}) — never both and never neither.</p>
 */
public class SkillServerAdapter extends ServerAdapter {

    private final Map<String, String> namespaceMode;

    public SkillServerAdapter(Capability capability, SkillServerSpec serverSpec) {
        super(capability, serverSpec);

        this.namespaceMode = buildNamespaceMode(capability, serverSpec);
        validateSkills(serverSpec, namespaceMode);

        Context context = new Context();
        context.getAttributes().put("skillServerSpec", serverSpec);
        context.getAttributes().put("namespaceMode", namespaceMode);
        if (getCapability().getSpec().getInfo() != null
                && getCapability().getSpec().getInfo().getLabel() != null) {
            context.getAttributes().put("capabilityName",
                    getCapability().getSpec().getInfo().getLabel());
        }

        Router router = new Router(context);
        router.attach("/skills", CatalogResource.class);
        router.attach("/skills/{name}", DetailResource.class);
        router.attach("/skills/{name}/download", DownloadResource.class);
        router.attach("/skills/{name}/contents", ContentsResource.class);
        TemplateRoute fileRoute =
                router.attach("/skills/{name}/contents/{file}", FileResource.class);
        fileRoute.getTemplate().getVariables().put("file", new Variable(Variable.TYPE_URI_PATH));

        Restlet chain = buildServerChain(router);
        initServer(serverSpec.getAddress(), serverSpec.getPort(), chain);
    }

    /**
     * Builds a lookup map from namespace name to adapter type ({@code "rest"} or {@code "mcp"})
     * by scanning the sibling {@link ServerSpec} entries in the capability. The skill adapter
     * itself is excluded.
     */
    private static Map<String, String> buildNamespaceMode(Capability capability,
            SkillServerSpec selfSpec) {
        Map<String, String> map = new HashMap<>();
        for (ServerSpec spec : capability.getSpec().getCapability().getExposes()) {
            if (spec == selfSpec) {
                continue;
            }
            if (spec instanceof RestServerSpec) {
                String ns = ((RestServerSpec) spec).getNamespace();
                if (ns != null) {
                    map.put(ns, "rest");
                }
            } else if (spec instanceof McpServerSpec) {
                String ns = ((McpServerSpec) spec).getNamespace();
                if (ns != null) {
                    map.put(ns, "mcp");
                }
            }
        }
        return map;
    }

    /**
     * Fail-fast validation: every tool must have exactly one of {@code from} or
     * {@code instruction}; {@code from} namespaces must resolve to a sibling adapter;
     * {@code instruction} tools require a skill-level {@code location}.
     */
    private static void validateSkills(SkillServerSpec serverSpec,
            Map<String, String> namespaceMode) {
        for (ExposedSkillSpec skill : serverSpec.getSkills()) {
            for (SkillToolSpec tool : skill.getTools()) {
                boolean hasFrom = tool.getFrom() != null;
                boolean hasInstruction = tool.getInstruction() != null;
                if (!hasFrom && !hasInstruction) {
                    throw new IllegalArgumentException("Skill tool '" + tool.getName()
                            + "' in skill '" + skill.getName()
                            + "' must define either 'from' or 'instruction'.");
                }
                if (hasFrom && hasInstruction) {
                    throw new IllegalArgumentException("Skill tool '" + tool.getName()
                            + "' in skill '" + skill.getName()
                            + "' cannot define both 'from' and 'instruction'.");
                }
                if (hasFrom) {
                    String ns = tool.getFrom().getSourceNamespace();
                    if (!namespaceMode.containsKey(ns)) {
                        throw new IllegalArgumentException("Skill tool '" + tool.getName()
                                + "' in skill '" + skill.getName()
                                + "' references unknown namespace '" + ns
                                + "'. Expected one of: " + namespaceMode.keySet());
                    }
                }
                if (hasInstruction && skill.getLocation() == null) {
                    throw new IllegalArgumentException("Skill tool '" + tool.getName()
                            + "' in skill '" + skill.getName()
                            + "' uses 'instruction' but the skill has no 'location' configured.");
                }
            }
        }
    }

    public SkillServerSpec getSkillServerSpec() {
        return (SkillServerSpec) getSpec();
    }

    @Override
    public void start() throws Exception {
        super.start();
        Context.getCurrentLogger().log(Level.INFO,
                "Skill Server started on " + getSkillServerSpec().getAddress() + ":"
                        + getSkillServerSpec().getPort() + " (namespace: "
                        + getSkillServerSpec().getNamespace() + ")");
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        Context.getCurrentLogger().log(Level.INFO,
                "Skill Server stopped on " + getSkillServerSpec().getAddress() + ":"
                        + getSkillServerSpec().getPort());
    }
}
