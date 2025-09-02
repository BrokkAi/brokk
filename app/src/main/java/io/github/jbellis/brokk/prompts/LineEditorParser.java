package io.github.jbellis.brokk.prompts;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.LineEdit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the typed, line-based edit format:
 *
 * <ul>
 *   <li>{@code <brk_delete_file path="..." />}
 *   <li>{@code <brk_edit_file path="..." type="insert" beginline=...>}
 *   <li>{@code <brk_edit_file path="..." type="replace" beginline=... endline=...>}
 *   <li>{@code <brk_edit_file path="..." type="delete_lines" beginline=... endline=...>}
 * </ul>
 *
 * Notes:
 * <ul>
 *   <li>`path` must be quoted (single or double quotes).
 *   <li>`type` is required for `brk_edit_file` and must be one of `insert`, `replace`, or
 *       `delete_lines`.
 *   <li>`beginline` is required for all `brk_edit_file` types.
 *   <li>`endline` is required for `replace` and `delete_lines`, and must be omitted for `insert`.
 *   <li>Closing tag `</brk_edit_file>` is required.
 * </ul>
 *
 * This parser is read-only: it does not touch the filesystem. It returns a mixed stream of
 * OutputParts (plain text, edit, delete). The materialization to ProjectFile happens in
 * `materializeEdits` to keep parsing independent from workspace state.
 */
public final class LineEditorParser {

    private static final Logger logger = LogManager.getLogger(LineEditorParser.class);

    public static final LineEditorParser instance = new LineEditorParser();

    private LineEditorParser() {}

    public enum Type { INSERT, REPLACE, DELETE_LINES, PREPEND, APPEND }

    // <brk_delete_file ... />
    private static final Pattern DELETE_TAG = Pattern.compile(
            "<\\s*brk_delete_file\\s+([^>/]*?)\\s*/\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // <brk_edit_file ...> (we do not capture body here; see parse loop)
    private static final Pattern EDIT_OPEN_TAG = Pattern.compile(
            "<\\s*brk_edit_file\\s+([^>]*?)>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // </brk_edit_file>
    private static final Pattern EDIT_CLOSE_TAG = Pattern.compile(
            "</\\s*brk_edit_file\\s*>",
            Pattern.CASE_INSENSITIVE);

    // Generic attribute parser: key=value where value may be in single or double quotes or be an unquoted token
    private static final Pattern ATTR = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))");

    // ---------------------------
    // Public result model
    // ---------------------------

    public sealed interface OutputPart permits OutputPart.Text, OutputPart.Edit, OutputPart.Delete {
        record Text(String text) implements OutputPart {}
        record Edit(String path, Type type, int beginLine, @Nullable Integer endLine, String content) implements OutputPart {}
        record Delete(String path) implements OutputPart {}
    }

    public record ExtendedParseResult(List<OutputPart> parts, @Nullable String parseError) {
        public boolean hasEdits() {
            for (var p : parts) {
                if (!(p instanceof OutputPart.Text)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Parse mixed content containing delete/edit tags and plain text.
     * Returns parts in input order plus a combined parseError string if any non-fatal issues occurred.
     */
    public ExtendedParseResult parse(String content) {
        var parts = new ArrayList<OutputPart>();
        var errors = new ArrayList<String>();

        int idx = 0;
        final int n = content.length();

        while (idx < n) {
            // Find next tag (either delete or edit)
            var nextDelete = findNext(DELETE_TAG, content, idx);
            var nextEditOpen = findNext(EDIT_OPEN_TAG, content, idx);

            var next = earliest(nextDelete, nextEditOpen);

            if (next == null) {
                // No more tags — remainder is plain text
                var tail = content.substring(idx);
                if (!tail.isEmpty()) {
                    parts.add(new OutputPart.Text(tail));
                }
                break;
            }

            // Emit any preceding plain text
            if (next.start() > idx) {
                parts.add(new OutputPart.Text(content.substring(idx, next.start())));
            }

            if (next.pattern() == DELETE_TAG) {
                // Self-closing delete tag
                var attrText = groupOrEmpty(next, 1);
                var attrs = parseAttributes(attrText);
                var path = attrs.get("path");
                if (path == null || path.isBlank()) {
                    errors.add("brk_delete_file missing required path attribute.");
                    // Stop on first structural error; keep the malformed tag as plain text
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                    break;
                } else {
                    parts.add(new OutputPart.Delete(path));
                }
                idx = next.end();
            } else {
                // Edit open tag
                var openAttrText = groupOrEmpty(next, 1);
                var attrs = parseAttributes(openAttrText);

                var path = attrs.get("path");
                if (path == null || path.isBlank()) {
                    errors.add("brk_edit_file missing required path attribute.");
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                    break;
                }

                var typeStr = attrs.get("type");
                if (typeStr == null) {
                    errors.add("brk_edit_file missing required type attribute.");
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                    break;
                }

                Type type;
                try {
                    type = Type.valueOf(typeStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add("brk_edit_file has unknown type: '" + typeStr + "'. Must be one of INSERT, REPLACE, DELETE_LINES.");
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                    break;
                }

                var bodyStart = next.end();
                var close = findNext(EDIT_CLOSE_TAG, content, bodyStart);
                if (close == null) {
                    errors.add("Missing closing </brk_edit_file> tag.");
                    parts.add(new OutputPart.Text(content.substring(next.start())));
                    break;
                }
                var body = content.substring(bodyStart, close.start());

                var begin = parseInt(attrs.get("beginline"));
                var end = parseInt(attrs.get("endline"));

                String error = null;
                if (type == Type.INSERT) {
                    if (begin == null) {
                        error = "brk_edit_file with type=\"insert\" must have a beginline attribute.";
                    } else if (end != null) {
                        error = "brk_edit_file with type=\"insert\" MUST NOT have an endline attribute.";
                    }
                } else if (type == Type.PREPEND || type == Type.APPEND) {
                    if (begin != null || end != null) {
                        error = "brk_edit_file with type=\"" + typeStr + "\" MUST NOT have beginline or endline attributes.";
                    }
                } else { // REPLACE or DELETE_LINES
                    if (begin == null || end == null) {
                        error = "brk_edit_file with type=\""+ typeStr +"\" must have beginline and endline attributes.";
                    } else if (begin > end) {
                        error = "brk_edit_file with type=\""+ typeStr +"\" must have beginline <= endline.";
                    } else if (type == Type.DELETE_LINES && !body.trim().isEmpty()) {
                        error = "brk_edit_file with type=\"delete_lines\" must have an empty body.";
                    }
                }

                if (error != null) {
                    errors.add(error);
                    parts.add(new OutputPart.Text(content.substring(next.start(), close.end())));
                    break;
                }

                parts.add(new OutputPart.Edit(path, type, requireNonNull(begin), end, type == Type.DELETE_LINES ? "" : body));
                idx = close.end();
            }
        }

        var error = errors.isEmpty() ? null : String.join("\n", errors);
        if (error != null && logger.isDebugEnabled()) {
            logger.debug("LineEditorParser parse errors:\n{}", error);
        }
        return new ExtendedParseResult(parts, error);
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private record Match(Pattern pattern, Matcher matcher) {
        int start() { return matcher.start(); }
        int end() { return matcher.end(); }
        String group(int i) { return matcher.group(i); }
    }

    private static @Nullable Match findNext(Pattern pattern, String text, int from) {
        var m = pattern.matcher(text);
        if (m.find(from)) {
            return new Match(pattern, m);
        }
        return null;
    }

    private static @Nullable Match earliest(@Nullable Match a, @Nullable Match b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.start() <= b.start() ? a : b;
    }

    private static String groupOrEmpty(Match m, int g) {
        return m.group(g);
    }

    private static Map<String, String> parseAttributes(String attrText) {
        var map = new HashMap<String, String>();
        var m = ATTR.matcher(attrText);
        while (m.find()) {
            var key = m.group(1).toLowerCase(Locale.ROOT);
            var val = Optional.ofNullable(m.group(2))
                    .or(() -> Optional.ofNullable(m.group(3)))
                    .or(() -> Optional.ofNullable(m.group(4)))
                    .orElse("");
            map.put(key, val);
        }
        return map;
    }

    private static @Nullable Integer parseInt(@Nullable String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Convert parsed OutputParts to concrete LineEdit instances by resolving paths
     * to ProjectFile via the provided IContextManager.
     */
    public static List<LineEdit> materializeEdits(ExtendedParseResult result, IContextManager cm) {
        var edits = new ArrayList<LineEdit>();
        for (var part : result.parts()) {
            if (part instanceof OutputPart.Delete(String path)) {
                var pf = cm.toFile(path);
                edits.add(new LineEdit.DeleteFile(pf));
            } else if (part instanceof OutputPart.Edit(var path, var type, var beginLine, var endLine, var content)) {
                var pf = cm.toFile(path);
                switch (type) {
                    case INSERT -> {
                        // For inserts, the engine uses endLine < beginLine.
                        // For a new file, this is normalized to beginline=1, endline=0.
                        if (pf.exists()) {
                            edits.add(new LineEdit.EditFile(pf, beginLine, beginLine - 1, content));
                        } else {
                            edits.add(new LineEdit.EditFile(pf, 1, 0, content));
                        }
                    }
                    case PREPEND -> {
                        // Insert at start of file; create file if missing
                        edits.add(new LineEdit.EditFile(pf, 1, 0, content));
                    }
                    case APPEND -> {
                        if (pf.exists()) {
                            int n = 0;
                            try {
                                var text = pf.read();
                                if (!text.isEmpty()) {
                                    if (text.endsWith("\n")) {
                                        var s = text.substring(0, text.length() - 1);
                                        if (!s.isEmpty()) {
                                            n = (int) s.lines().count();
                                        }
                                    } else {
                                        n = (int) text.lines().count();
                                    }
                                }
                            } catch (IOException e) {
                                n = 0;
                            }
                            edits.add(new LineEdit.EditFile(pf, n + 1, n, content));
                        } else {
                            edits.add(new LineEdit.EditFile(pf, 1, 0, content));
                        }
                    }
                    case REPLACE -> edits.add(new LineEdit.EditFile(pf, beginLine, requireNonNull(endLine), content));
                    case DELETE_LINES -> edits.add(new LineEdit.EditFile(pf, beginLine, requireNonNull(endLine), ""));
                }
            }
        }
        return edits;
    }

    // -----------------------------------------------------------------------------
    // Prompt surface (analogous to EditBlockParser's prompt API)
    // -----------------------------------------------------------------------------

    /**
     * Canonical rules for the line-edit tag format.
     * Keep this close to the parser to avoid drift between "what we ask for" and "what we accept".
     */
    public String lineEditFormatInstructions() {
        return """
        # Line-Edit Tag Rules (use exactly this format)

        Provide file edits using only these tags (no backticks, no diff format, no JSON, no YAML):
        - <brk_edit_file path="<full/path>" type="insert" beginline=<int>> ...raw code... </brk_edit_file>
        - <brk_edit_file path="<full/path>" type="replace" beginline=<int> endline=<int>> ...raw code... </brk_edit_file>
        - <brk_edit_file path="<full/path>" type="delete_lines" beginline=<int> endline=<int>></brk_edit_file>
        - <brk_edit_file path="<full/path>" type="prepend"> ...raw code... </brk_edit_file>
        - <brk_edit_file path="<full/path>" type="append"> ...raw code... </brk_edit_file>
        - <brk_delete_file path="<full/path>" />

        Conventions:
        - Lines are 1-based and for REPLACE/DELETE_LINES the range [beginline, endline] is inclusive.
        - INSERT: provide beginline only. The new text is inserted at index (beginline - 1). For a new file, use beginline=1.
        - PREPEND: insert the body at the start of the file. Do not provide beginline/endline. Creates the file if missing.
        - APPEND: insert the body at the end of the file. Do not provide beginline/endline. Creates the file if missing.
        - REPLACE: supply beginline and endline; the range is replaced with the body.
        - DELETE_LINES: supply beginline and endline with an empty body.
        - Deleting a file: use <brk_delete_file path="..."/>.

        Requirements:
        - type attribute is REQUIRED and must be one of: insert, replace, delete_lines, prepend, append.
        - path attribute must be the FULL file path (exact string you see in the workspace) and must be quoted.
        - beginline is REQUIRED and must be >= 1 for type="insert".
        - endline is REQUIRED only for type="replace" and type="delete_lines"; it MUST be omitted for type="insert".
        - beginline and endline MUST be omitted for type="prepend" and type="append".
        - Close edit blocks with </brk_edit_file>.
        - DO NOT wrap edits in Markdown code fences (```), JSON, or any other wrapper.
        - DO NOT escape characters inside the edit body; raw code goes between the open/close tags.
        - DO NOT use unified diff, +/- prefixes, or any other diff-like markers.

        Quality:
        - Prefer SMALL, PRECISE edits that touch the minimal unique range of lines.
        - Avoid overlapping edits in the same file; combine adjacent lines into a single edit.
        - If you need multiple changes in one file, emit multiple small <brk_edit_file> blocks.
        - Use ASCII quotes only (no "smart quotes").

        Creating a brand new file:
        - Use type="insert" with beginline=1 and the entire file content in the body.

        Moving code between files:
        - Use two edits: (1) delete_lines for the old range, (2) insert at the new location.

        If a file is read-only or not present, ask the user to make it editable or add it to the workspace.
        """.stripIndent();
    }

    /**
     * Example messages that demonstrate high-quality usage of <brk_...> tags.
     * Mirrors the fidelity of the SEARCH/REPLACE examples: short rationale + minimal, precise edits.
     */
    public List<ChatMessage> exampleMessages() {
        return List.of(
                // Example 1: Replace a recursive factorial with math.factorial via three small edits.
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
                To make this change we will:
                1) Import the math module.
                2) Remove the old recursive implementation.
                3) Replace the call site with math.factorial(n).

                <brk_edit_file path="mathweb/flask/app.py" type="prepend">import math
                </brk_edit_file>

                <!-- delete the old factorial impl by replacing its range with an empty body -->
                <brk_edit_file path="mathweb/flask/app.py" type="delete_lines" beginline=12 endline=18></brk_edit_file>

                <!-- update the return to call math.factorial -->
                <brk_edit_file path="mathweb/flask/app.py" type="replace" beginline=25 endline=25>return str(math.factorial(n))
                </brk_edit_file>
                """.stripIndent()),

                // Example 2: New file + adjust existing file with minimal edits.
                new UserMessage("Refactor hello() into its own file."),
                new AiMessage("""
                We will:
                1) Create a new hello.py with hello().
                2) Replace the function in main.py with an import.
                3) Append a usage comment at the end of main.py.

                <!-- create new file: insert entire file content at line 1 -->
                <brk_edit_file path="hello.py" type="insert" beginline=1>def hello():
                    \"\"\"print a greeting\"\"\"
                    print("hello")
                </brk_edit_file>

                <!-- delete original function body in main.py by making the range empty -->
                <brk_edit_file path="main.py" type="delete_lines" beginline=10 endline=15></brk_edit_file>

                <!-- prepend the import at the top of main.py -->
                <brk_edit_file path="main.py" type="prepend">from hello import hello
                </brk_edit_file>

                <!-- append a usage note at the end of main.py -->
                <brk_edit_file path="main.py" type="append"># Usage: hello()
                </brk_edit_file>
                """.stripIndent())
        );
    }

    /**
     * Build the full instruction payload for the LLM: rules + reminders + the user goal.
     * Matches the structure of EditBlockParser.instructions while targeting line-edit tags.
     */
    public String instructions(String input, @Nullable ProjectFile file, String reminder) {
        var targetAttr = (file == null) ? "" : " target=\"%s\"".formatted(file);
        return """
        <rules>
        %s

        Guidance:
        - Think first; then produce a few short sentences explaining the changes.
        - Then provide ONLY <brk_edit_file> / <brk_delete_file> tags for the actual edits.
        - Keep each edit as small as possible while still unambiguous.
        - If multiple, separate edits are required, emit multiple tag blocks.

        %s
        </rules>

        <goal%s>
        %s
        </goal>
        """.stripIndent().formatted(lineEditFormatInstructions(), reminder, targetAttr, input);
    }

    /**
     * Pretty-print a parsed OutputPart for logs and failure messages.
     */
    public static String repr(OutputPart part) {
        if (part instanceof OutputPart.Delete(String path)) {
            return reprDelete(path);
        }
        if (part instanceof OutputPart.Edit(var path, var type, var begin, var end, var content)) {
            return reprEdit(path, type, begin, end, content);
        }
        if (part instanceof OutputPart.Text(String text)) {
            return text;
        }
        return part.toString();
    }

    /**
     * Pretty-print a concrete LineEdit for logs and failure/continuation messages.
     * Centralizes formatting so agents don't reconstruct tags manually.
     */
    public static String repr(LineEdit edit) {
        if (edit instanceof LineEdit.DeleteFile(ProjectFile file)) {
            return reprDelete(canonicalPath(file));
        }
        if (edit instanceof LineEdit.EditFile(ProjectFile file, int beginLine, int endLine, String content)) {
            Type type;
            Integer endlineAttr = endLine;
            if (endLine < beginLine) {
                type = Type.INSERT;
                endlineAttr = null;
            } else if (content.isEmpty()) {
                type = Type.DELETE_LINES;
            } else {
                type = Type.REPLACE;
            }
            return reprEdit(canonicalPath(file), type, beginLine, endlineAttr, content);
        }
        return edit.toString();
    }

    private static String reprDelete(String path) {
        return "<brk_delete_file path=\"%s\" />".formatted(path);
    }

    private static String reprEdit(String path, Type type, int begin, @Nullable Integer end, String content) {
        var typeStr = type.toString().toLowerCase(Locale.ROOT);
        var endlineAttr = "";
        if (type == Type.REPLACE || type == Type.DELETE_LINES) {
            endlineAttr = " endline=%d".formatted(requireNonNull(end));
        }

        return """
               <brk_edit_file path="%s" type="%s" beginline=%d%s>
               %s
               </brk_edit_file>
               """.stripIndent().formatted(path, typeStr, begin, endlineAttr, content);
    }

    /**
     * Returns the canonical string path for a ProjectFile, used in tag formatting.
     * This uses ProjectFile.toString(), which should correspond to the full workspace path.
     */
    private static String canonicalPath(ProjectFile file) {
        return file.toString();
    }
}
