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
package io.ikanos.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rewrites a capability document written before steps became a list.
 *
 * <p>Two changes are made, and nothing else is touched:</p>
 * <ul>
 * <li>a {@code steps:} map keyed by step name becomes a list whose items carry the name as
 * {@code name:}, in the same order;</li>
 * <li>the {@code call:} of a step becomes {@code what:}.</li>
 * </ul>
 *
 * <p>YAML is rewritten as text, line by line, so comments, quoting and layout are kept. A
 * {@code steps:} block is changed only when each of its entries declares a {@code type}, so a key
 * named {@code steps} elsewhere (in a {@code with:} map, for example) is left alone. JSON is
 * rewritten through a tree, which keeps the order of its fields.</p>
 *
 * <p>Both rewrites are idempotent: a document already in the list form comes back unchanged.</p>
 */
final class StepsMigrator {

    private static final Pattern STEPS_KEY =
            Pattern.compile("^(\\s*)(- )?steps:\\s*(#.*)?$");
    private static final Pattern MAP_ENTRY =
            Pattern.compile("^(\\s*)([A-Za-z0-9_-]+|\"[^\"]*\"|'[^']*'):(\\s+(.*))?$");
    private static final Pattern TYPE_KEY = Pattern.compile("^\\s*type:");
    private static final Pattern INLINE_TYPE = Pattern.compile("[{,]\\s*type\\s*:");
    private static final Pattern CALL_KEY = Pattern.compile("^(\\s*(?:- )?)call:");
    private static final Pattern INLINE_CALL = Pattern.compile("([{,]\\s*)call(\\s*:)");

    private StepsMigrator() {
    }

    /**
     * Rewrites a YAML document.
     *
     * @param text the document
     * @return the rewritten document, equal to {@code text} when nothing needed a change
     */
    static String migrateYaml(String text) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        boolean trailingNewline = text.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (trailingNewline) {
            lines.remove(lines.size() - 1);
        }
        List<String> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            out.add(line);
            i++;
            Matcher key = STEPS_KEY.matcher(line);
            if (!key.matches()) {
                continue;
            }
            int keyIndent = key.group(1).length() + (key.group(2) != null ? 2 : 0);
            int first = nextContent(lines, i);
            if (first >= 0 && indent(lines.get(first)) == keyIndent
                    && lines.get(first).startsWith("- ", keyIndent)) {
                // A list written at the key's own indent, as PyYAML writes one.
                int end = listEnd(lines, first, keyIndent);
                List<String> rewritten = renameCalls(lines.subList(i, end), keyIndent);
                if (rewritten != null) {
                    out.addAll(rewritten);
                    i = end;
                }
                continue;
            }
            if (first < 0 || indent(lines.get(first)) <= keyIndent) {
                continue;
            }
            int childIndent = indent(lines.get(first));
            int end = blockEnd(lines, first, childIndent);
            List<String> block = lines.subList(i, end);
            List<String> rewritten = lines.get(first).substring(childIndent).startsWith("- ")
                    ? renameCalls(block, childIndent)
                    : toList(block, childIndent);
            if (rewritten == null) {
                // Not a step map, such as a resource named 'steps': read on inside it, where a
                // real 'steps:' may be nested.
                continue;
            }
            out.addAll(rewritten);
            i = end;
        }
        String result = String.join(newline, out);
        return trailingNewline ? result + newline : result;
    }

    /**
     * Rewrites a JSON document.
     *
     * @param text the document
     * @return the rewritten document, or {@code text} itself when nothing needed a change
     */
    static String migrateJson(String text) {
        ObjectMapper mapper = IkanosMappers.json();
        JsonNode root = mapper.readTree(text);
        if (!migrateNode(root)) {
            return text;
        }
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        return text.endsWith("\n") ? json + System.lineSeparator() : json;
    }

    /** Rewrites the steps found under {@code node}; returns whether anything changed. */
    static boolean migrateNode(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            JsonNode steps = object.get("steps");
            if (steps instanceof ObjectNode map && isStepMap(map)) {
                ArrayNode list = object.arrayNode();
                for (Map.Entry<String, JsonNode> entry : map.properties()) {
                    ObjectNode step = object.objectNode();
                    step.put("name", entry.getKey());
                    entry.getValue().properties()
                            .forEach(field -> step.set(field.getKey(), field.getValue()));
                    list.add(step);
                }
                object.set("steps", list);
                changed = true;
            }
            if (object.get("steps") instanceof ArrayNode list && isStepList(list)) {
                for (JsonNode step : list) {
                    changed |= renameCall(step);
                }
            }
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                changed |= migrateNode(entry.getValue());
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                changed |= migrateNode(item);
            }
        }
        return changed;
    }

    /**
     * Finds the {@code steps} that {@link #migrateNode} would rewrite.
     *
     * @param root a parsed document
     * @return the path of each such {@code steps}, as {@code $.capability.aggregates[0]...}
     */
    static List<String> findEarlierForms(JsonNode root) {
        List<String> paths = new ArrayList<>();
        collectEarlierForms(root, "$", paths);
        return paths;
    }

    private static void collectEarlierForms(JsonNode node, String path, List<String> paths) {
        if (node instanceof ObjectNode object) {
            JsonNode steps = object.get("steps");
            if ((steps instanceof ObjectNode map && isStepMap(map))
                    || (steps instanceof ArrayNode list && isStepList(list) && hasCall(list))) {
                paths.add(path + ".steps");
            }
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                collectEarlierForms(entry.getValue(), path + "." + entry.getKey(), paths);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                collectEarlierForms(array.get(i), path + "[" + i + "]", paths);
            }
        }
    }

    private static boolean hasCall(ArrayNode steps) {
        for (JsonNode step : steps) {
            if (step.isObject() && step.has("call") && !step.has("what")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a list is a list of steps: each item an object that declares a {@code type}. A
     * {@code steps} list elsewhere, such as in a request body's data, is left alone.
     */
    private static boolean isStepList(ArrayNode list) {
        if (list.isEmpty()) {
            return false;
        }
        for (JsonNode item : list) {
            if (!item.isObject() || !item.has("type")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStepMap(ObjectNode map) {
        if (map.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> entry : map.properties()) {
            if (!entry.getValue().isObject() || !entry.getValue().has("type")) {
                return false;
            }
        }
        return true;
    }

    /** Renames the {@code call} field of a step to {@code what}, keeping its place. */
    private static boolean renameCall(JsonNode node) {
        if (!(node instanceof ObjectNode step) || !step.has("call") || step.has("what")) {
            return false;
        }
        ObjectNode copy = step.objectNode();
        step.properties().forEach(field -> copy.set(
                "call".equals(field.getKey()) ? "what" : field.getKey(), field.getValue()));
        step.removeAll();
        step.setAll(copy);
        return true;
    }

    /**
     * Turns a {@code steps:} map block into a list block, or returns {@code null} when an entry
     * declares no {@code type} (the block is then not a step map).
     */
    private static List<String> toList(List<String> block, int childIndent) {
        List<String> out = new ArrayList<>(block.size());
        int i = 0;
        while (i < block.size()) {
            String line = block.get(i);
            if (isBlankOrComment(line) || indent(line) != childIndent) {
                out.add(line);
                i++;
                continue;
            }
            Matcher entry = MAP_ENTRY.matcher(line);
            if (!entry.matches()) {
                return null;
            }
            String name = unquote(entry.group(2));
            String value = entry.group(4) != null ? entry.group(4).strip() : "";
            String pad = " ".repeat(childIndent);
            int bodyEnd = i + 1;
            while (bodyEnd < block.size() && (isBlankOrComment(block.get(bodyEnd))
                    || indent(block.get(bodyEnd)) > childIndent)) {
                bodyEnd++;
            }
            // Leave blank lines and comments that sit just before the next entry where they are.
            int lastBody = bodyEnd;
            while (lastBody > i + 1 && isBlankOrComment(block.get(lastBody - 1))
                    && indent(block.get(lastBody - 1)) <= childIndent) {
                lastBody--;
            }
            if (value.startsWith("{")) {
                if (!INLINE_TYPE.matcher(value).find()) {
                    return null;
                }
                String rest = value.substring(1).stripLeading();
                out.add(renameInlineCall(
                        pad + "- {name: " + name + (rest.startsWith("}") ? rest : ", " + rest)));
                out.addAll(block.subList(i + 1, bodyEnd));
                i = bodyEnd;
                continue;
            }
            if (!value.isEmpty() && !value.startsWith("#")) {
                return null;
            }
            List<String> body = block.subList(i + 1, lastBody);
            int bodyIndent = -1;
            boolean typed = false;
            for (String bodyLine : body) {
                if (!isBlankOrComment(bodyLine)) {
                    if (bodyIndent < 0) {
                        bodyIndent = indent(bodyLine);
                    }
                    if (indent(bodyLine) == bodyIndent && TYPE_KEY.matcher(bodyLine).find()) {
                        typed = true;
                    }
                }
            }
            if (!typed) {
                return null;
            }
            out.add(pad + "- name: " + name + (value.isEmpty() ? "" : " " + value));
            int shift = childIndent + 2 - bodyIndent;
            for (String bodyLine : body) {
                String moved = isBlank(bodyLine) ? bodyLine : reindent(bodyLine, shift,
                        childIndent + 2);
                if (!isBlankOrComment(moved) && indent(moved) == childIndent + 2) {
                    moved = CALL_KEY.matcher(moved).replaceFirst("$1what:");
                }
                out.add(moved);
            }
            out.addAll(block.subList(lastBody, bodyEnd));
            i = bodyEnd;
        }
        return out;
    }

    /**
     * Renames {@code call:} to {@code what:} in a block that is already a list of steps, or
     * returns {@code null} when an item declares no {@code type} (the block is then not a step
     * list, such as a {@code steps} list in a request body's data).
     */
    private static List<String> renameCalls(List<String> block, int childIndent) {
        if (!isTypedList(block, childIndent)) {
            return null;
        }
        List<String> out = new ArrayList<>(block.size());
        for (String line : block) {
            String renamed = line;
            if (!isBlankOrComment(line)) {
                int indent = indent(line);
                boolean item = line.startsWith("- ", indent);
                if (indent == childIndent && item) {
                    renamed = CALL_KEY.matcher(line).replaceFirst("$1what:");
                    renamed = renameInlineCall(renamed);
                } else if (indent == childIndent + 2) {
                    renamed = CALL_KEY.matcher(line).replaceFirst("$1what:");
                }
            }
            out.add(renamed);
        }
        return out;
    }

    /** Whether each item of a YAML list block declares a {@code type}. */
    private static boolean isTypedList(List<String> block, int childIndent) {
        int items = 0;
        int typed = 0;
        boolean current = false;
        for (String line : block) {
            if (isBlankOrComment(line)) {
                continue;
            }
            int indent = indent(line);
            if (indent == childIndent && line.startsWith("- ", indent)) {
                if (items > 0 && current) {
                    typed++;
                }
                items++;
                String first = line.substring(indent + 2).strip();
                current = first.startsWith("type:")
                        || (first.startsWith("{") && INLINE_TYPE.matcher(first).find());
            } else if (indent == childIndent + 2 && TYPE_KEY.matcher(line).find()) {
                current = true;
            }
        }
        if (items > 0 && current) {
            typed++;
        }
        return items > 0 && typed == items;
    }

    private static String renameInlineCall(String line) {
        int brace = line.indexOf('{');
        if (brace < 0) {
            return line;
        }
        return line.substring(0, brace)
                + INLINE_CALL.matcher(line.substring(brace)).replaceFirst("$1what$2");
    }

    private static int blockEnd(List<String> lines, int from, int childIndent) {
        int end = from;
        int last = from;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (!isBlankOrComment(line)) {
                if (indent(line) < childIndent) {
                    break;
                }
                last = end;
            }
            end++;
        }
        return last + 1;
    }

    /** The end of a list whose items sit at {@code indent}, the indent of its key. */
    private static int listEnd(List<String> lines, int from, int indent) {
        int end = from;
        int last = from;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (!isBlankOrComment(line)) {
                int lineIndent = indent(line);
                if (lineIndent < indent
                        || (lineIndent == indent && !line.startsWith("- ", indent))) {
                    break;
                }
                last = end;
            }
            end++;
        }
        return last + 1;
    }

    private static int nextContent(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!isBlankOrComment(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String reindent(String line, int shift, int minimum) {
        int indent = indent(line);
        int target = Math.max(minimum, indent + shift);
        return " ".repeat(target) + line.substring(indent);
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && (key.startsWith("\"") || key.startsWith("'"))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean isBlank(String line) {
        return line.isBlank();
    }

    private static boolean isBlankOrComment(String line) {
        String stripped = line.strip();
        return stripped.isEmpty() || stripped.startsWith("#");
    }
}
