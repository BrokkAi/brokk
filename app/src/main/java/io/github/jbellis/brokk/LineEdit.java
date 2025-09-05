package io.github.jbellis.brokk;

import io.github.jbellis.brokk.prompts.LineEditorParser;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Data model for line-based editing operations.
 *
 * Now path-based (String path), so the parser does not depend on the filesystem. Resolution to
 * {@code ProjectFile} happens later (e.g., in LineEditor / CodeAgent).
 *
 * Semantics:
 * - DeleteFile: removes a file from the workspace.
 * - EditFile: replace the inclusive range [beginLine, endLine] with given content.
 *   Insertion is modeled by endLine < beginLine (e.g., beginLine=1,endLine=0 inserts at start).
 *
 * Lines are 1-based.
 */
public sealed interface LineEdit permits LineEdit.DeleteFile, LineEdit.EditFile {
    static void renderBody(StringBuilder sb, List<String> lines) {
        for (var l : lines) {
            if (l.equals(".")) sb.append("\\.\n");
            else if (l.equals("\\")) sb.append("\\\\\n");
            else sb.append(l).append('\n');
        }
        sb.append(".\n");
    }

    /**
     * Human-readable form of this edit using BRK_EDIT_EX / BRK_EDIT_RM.
     * Intended for logging and for LLM retry prompts ("last good edit").
     */
    default String repr() {
        if (this instanceof DeleteFile(String path)) {
            return "BRK_EDIT_RM " + path;
        }
        if (this instanceof EditFile(
                String path, int beginLine, int endLine, String content, Anchor beginAnchor,
                @Nullable Anchor endAnchor
        )) {
            var sb = new StringBuilder();
            sb.append("BRK_EDIT_EX ").append(path).append('\n');

            if (endLine < beginLine) {
                // insertion
                String addr = (beginLine == Integer.MAX_VALUE) ? "$" : LineEditorParser.addrToString(beginLine - 1);
                sb.append(addr).append(" a\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                renderBody(sb, content.isEmpty() ? List.of() : content.lines().toList());
            } else if (content.isEmpty()) {
                // delete
                sb.append(LineEditorParser.addrToString(beginLine)).append(',')
                        .append(LineEditorParser.addrToString(endLine)).append(" d\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                if (endAnchor != null && !endAnchor.address().isBlank()) {
                    sb.append("@").append(endAnchor.address()).append("| ").append(endAnchor.content()).append('\n');
                }
            } else {
                // change
                sb.append(LineEditorParser.addrToString(beginLine)).append(',')
                        .append(LineEditorParser.addrToString(endLine)).append(" c\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                if (endAnchor != null) {
                    sb.append("@").append(endAnchor.address()).append("| ").append(endAnchor.content()).append('\n');
                }
                renderBody(sb, content.lines().toList());
            }
            sb.append("BRK_EDIT_EX_END");
            return sb.toString();
        }
        return toString();
    }

    /**
     * Concise summary for display in errors/prompts; elides the body as "[...]".
     */
    default String summary() {
        if (this instanceof DeleteFile(String path)) {
            return "BRK_EDIT_RM " + path;
        }
        if (this instanceof EditFile(
                String path, int beginLine, int endLine, String content, Anchor beginAnchor,
                @Nullable Anchor endAnchor
        )) {
            var sb = new StringBuilder();
            sb.append("BRK_EDIT_EX ").append(path).append('\n');

            if (endLine < beginLine) {
                // insertion
                String addr = (beginLine == Integer.MAX_VALUE) ? "$" : LineEditorParser.addrToString(beginLine - 1);
                sb.append(addr).append(" a\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                sb.append("[...]\n");
                sb.append("BRK_EDIT_EX_END");
                return sb.toString();
            } else if (content.isEmpty()) {
                // delete (no body to elide)
                sb.append(LineEditorParser.addrToString(beginLine)).append(',')
                        .append(LineEditorParser.addrToString(endLine)).append(" d\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                if (endAnchor != null && !endAnchor.address().isBlank()) {
                    sb.append("@").append(endAnchor.address()).append("| ").append(endAnchor.content()).append('\n');
                }
                sb.append("BRK_EDIT_EX_END");
                return sb.toString();
            } else {
                // change with elided body
                sb.append(LineEditorParser.addrToString(beginLine)).append(',')
                        .append(LineEditorParser.addrToString(endLine)).append(" c\n");
                sb.append("@").append(beginAnchor.address()).append("| ").append(beginAnchor.content()).append('\n');
                if (endAnchor != null) {
                    sb.append("@").append(endAnchor.address()).append("| ").append(endAnchor.content()).append('\n');
                }
                sb.append("[...]\n");
                sb.append("BRK_EDIT_EX_END");
                return sb.toString();
            }
        }
        return toString();
    }

    /** Path to the file being edited/deleted, relative to the project root. */
    String file();

    /** Delete the specified file (by path). */
    record DeleteFile(String file) implements LineEdit { }

    /**
     * Anchor for validating addresses. address is the original address ("0", "1", ..., "$");
     * content is the exact current line text expected at that address.
     */
    record Anchor(String address, String content) { }

    /**
     * Replace a range of lines in the specified file with the provided content.
     * Lines are 1-based and the range is inclusive.
     *
     * Insertion is modeled by {@code endLine < beginLine} (e.g., {@code beginLine=1, endLine=0} inserts at the start).
     * Anchors validate current file content at the addressed positions before applying. For deletions and insertions,
     * only the begin anchor is validated; for changes, both begin and end anchors are validated (for single-line changes,
     * the end anchor should equal the begin anchor).
     */
    record EditFile(String file,
                    int beginLine,
                    int endLine,
                    String content,
                    Anchor beginAnchor,
                    @Nullable Anchor endAnchor) implements LineEdit {
        public EditFile {
            assert beginLine >= 1 : "beginLine must be >= 1";
            assert endLine >= 0 : "endLine must be >= 0";
        }
    }
}
