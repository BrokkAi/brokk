package io.github.jbellis.brokk.prompts;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.LineEdit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the line-based edit format:
 *
 * - <brk_delete_file path="..." />
 * - <brk_edit_file path="..." beginline=1 endline=3>...content...</brk_edit_file>
 *
 * Notes:
 * - path must be quoted (single or double quotes)
 * - beginline/endline must be non-negative integers (1-based for beginline, inclusive range)
 * - Closing tag </brk_edit_file> is required. We also accept </brk_update_file> for compatibility.
 *
 * This parser is read-only: it does not touch the filesystem. It returns a mixed stream
 * of OutputParts (plain text, edit, delete). The materialization to ProjectFile happens
 * in materializeEdits to keep parsing independent from workspace state.
 */
public final class LineEditorParser {

    private static final Logger logger = LogManager.getLogger(LineEditorParser.class);

    public static final LineEditorParser instance = new LineEditorParser();

    private LineEditorParser() {}


    // <brk_delete_file ... />
    private static final Pattern DELETE_TAG = Pattern.compile(
            "<\\s*brk_delete_file\\s+([^>/]*?)\\s*/\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // <brk_edit_file ...> (we do not capture body here; see parse loop)
    private static final Pattern EDIT_OPEN_TAG = Pattern.compile(
            "<\\s*brk_edit_file\\s+([^>]*?)>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // </brk_edit_file> or </brk_update_file> (compat)
    private static final Pattern EDIT_CLOSE_TAG = Pattern.compile(
            "</\\s*(brk_edit_file|brk_update_file)\\s*>",
            Pattern.CASE_INSENSITIVE);

    // Generic attribute parser: key=value where value may be in single or double quotes or be an unquoted token
    private static final Pattern ATTR = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))");

    // ---------------------------
    // Public result model
    // ---------------------------

    public sealed interface OutputPart permits OutputPart.Text, OutputPart.Edit, OutputPart.Delete {
        record Text(String text) implements OutputPart {}
        record Edit(String path, int beginLine, int endLine, String content) implements OutputPart {}
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
                    // Treat as plain text to avoid losing data
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                } else {
                    parts.add(new OutputPart.Delete(path));
                }
                idx = next.end();
            } else {
                // Edit open tag
                var openAttrText = groupOrEmpty(next, 1);
                var attrs = parseAttributes(openAttrText);

                var path = attrs.get("path");
                var begin = parseInt(attrs.get("beginline"));
                var end = parseInt(attrs.get("endline"));

                if (path == null || path.isBlank() || begin == null || end == null) {
                    errors.add("brk_edit_file missing one of required attributes: path, beginline, endline.");
                    // Treat as plain text (open tag only)
                    parts.add(new OutputPart.Text(content.substring(next.start(), next.end())));
                    idx = next.end();
                    continue;
                }

                // Find closing tag
                var bodyStart = next.end();
                var close = findNext(EDIT_CLOSE_TAG, content, bodyStart);
                if (close == null) {
                    errors.add("Missing closing </brk_edit_file> tag.");
                    // Treat entire rest as plain text
                    parts.add(new OutputPart.Text(content.substring(next.start())));
                    break;
                }

                var body = content.substring(bodyStart, close.start());
                parts.add(new OutputPart.Edit(path, begin, end, body));
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
            } else if (part instanceof OutputPart.Edit(String path, int beginLine, int endLine, String content)) {
                var pf = cm.toFile(path);
                edits.add(new LineEdit.EditFile(pf, beginLine, endLine, content));
            }
        }
        return edits;
    }
}
