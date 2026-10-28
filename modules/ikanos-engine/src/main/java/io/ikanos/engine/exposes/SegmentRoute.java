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
package io.ikanos.engine.exposes;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.routing.TemplateRoute;

/**
 * Route that matches a literal path prefix on whole path segments: {@code /mcp} matches
 * {@code /mcp}, {@code /mcp/tools} and {@code /mcp?x=1}, but not {@code /mcpx}. Restlet's
 * {@link Template#MODE_STARTS_WITH} matches characters, so this route scores zero when the match
 * does not end on a segment boundary. Used with {@link Router#MODE_BEST_MATCH}, the longest
 * matching prefix wins and the empty prefix (the root mount) catches the rest.
 *
 * <p>Restlet 3 may add {@code Template.MODE_STARTS_WITH_SEGMENT}
 * (restlet/restlet-framework-java#1564), which would replace this class.</p>
 */
class SegmentRoute extends TemplateRoute {

    private final String prefix;

    /**
     * @param router the parent router
     * @param prefix a literal path prefix such as {@code /mcp}, or {@code ""} for the root
     * @param next the restlet that handles matching requests
     */
    SegmentRoute(Router router, String prefix, Restlet next) {
        super(router, prefix, next);
        this.prefix = prefix;
        getTemplate().setMatchingMode(Template.MODE_STARTS_WITH);
    }

    @Override
    public float score(Request request, Response response) {
        float result = super.score(request, response);
        if (result == 0F || prefix.isEmpty()) {
            return result;
        }

        String remainingPart = request.getResourceRef().getRemainingPart(false, isMatchingQuery());
        return isSegmentBoundary(remainingPart, prefix.length()) ? result : 0F;
    }

    /**
     * @return {@code true} when the character at {@code index} of {@code path} ends a path
     *         segment: the end of the path, or a {@code /}, {@code ?}, {@code ;} or {@code #}
     */
    static boolean isSegmentBoundary(String path, int index) {
        if (path == null || index > path.length()) {
            return false;
        }
        return index == path.length() || "/?;#".indexOf(path.charAt(index)) >= 0;
    }

}
