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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ED-mode parser for Brokk line-edit fences.
 *
 * This class parses mixed content containing BRK_EDIT_EX/BRK_EDIT_EX_END blocks (with ED commands),
 * BRK_EDIT_RM lines (file deletes), and passthrough plain text. It is read-only and does not
 * access the filesystem; translation to LineEdit operations happens in {@link #materializeEdits}.
 *
 * Input grammar (high level):
 * - Fences are ALL CAPS and appear alone on their own lines:
 *   - "BRK_EDIT_EX &lt;path&gt;" ... commands ... "BRK_EDIT_EX_END"
 *   - "BRK_EDIT_RM &lt;path&gt;"
 * - &lt;path&gt; is the remainder of the fence line after the first space and may contain spaces.
 * - Supported commands (numeric addresses only; 1-based):
 *   - "n a"           append after line n (0 a allowed); body required
 *   - "n[,m] c"       replace inclusive range with body
 *   - "n[,m] d"       delete inclusive range (no body)
 * - Body for i/a/c ends with a single '.' on a line by itself. For the final body in a block,
 *   that terminating dot may be omitted.
 * - Escaping: a literal "." line is written as "\.", and a literal "\" line as "\\"
 * - Other ED commands (e.g., w, q) are ignored. Shell "!" is ignored and reported as a parse warning.
 *
 * Legacy typed tags (&lt;brk_edit_file&gt; ... &lt;/brk_edit_file&gt; and variants) are not supported by
 * this parser. If detected, the entire input is treated as plain text and a parse error is recorded
 * so the caller can display a helpful message.
 *
 * The parse result is a sequence of {@code OutputPart} objects ({@code Text}, {@code EdBlock}, {@code Delete}).
 * Use {@link #materializeEdits} to convert them into concrete {@link io.github.jbellis.brokk.LineEdit}
 * operations. Edit operations are sorted per file in descending line order to avoid line-number shifts
 * when applied.
 */
public final class LineEditorParser {

    private static final Logger logger = LogManager.getLogger(LineEditorParser.class);

    public static final LineEditorParser instance = new LineEditorParser();

    private LineEditorParser() {}

    public enum Type { INSERT, REPLACE, DELETE_LINES, PREPEND, APPEND }

    // ---------------------------
    // Public result model
    // ---------------------------

    public sealed interface OutputPart permits OutputPart.Text, OutputPart.EdBlock, OutputPart.Delete {
        /** Plain text outside of any BRK_EDIT fences */
        record Text(String text) implements OutputPart {}

        /**
         * One BRK_EDIT_EX block for a single file. It may contain multiple ED commands.
         * Commands must be expressed with absolute numeric addresses (no regex, no navigation).
         */
        record EdBlock(String path, List<EdCommand> commands) implements OutputPart {}

        /** One BRK_EDIT_RM line (remove a file). */
        record Delete(String path) implements OutputPart {}
    }

    /** ED commands supported in the BRK_EDIT_EX block. */
    public sealed interface EdCommand
            permits EdCommand.AppendAfter, EdCommand.ChangeRange, EdCommand.DeleteRange {

        /** n a — append after line n; body required; mandatory anchor "n: content". */
        record AppendAfter(int line, List<String> body, String anchorLine, String anchorContent) implements EdCommand {}

        /**
         * n[,m] c — replace inclusive range; body required; anchors:
         * - If only n is provided, exactly one anchor "n: content" (end anchor equals begin).
         * - If n,m are provided, two anchors "n: content" and "m: content".
         *   Anchors may be omitted only for 0 / $ addresses.
         */
        record ChangeRange(int begin, int end, List<String> body,
                           String beginAnchorLine, String beginAnchorContent,
                           String endAnchorLine, String endAnchorContent) implements EdCommand {}

        /**
         * n[,m] d — delete inclusive range; anchors:
         * - If only n is provided, exactly one anchor "n: content".
         * - If n,m are provided, two anchors "n: content" and "m: content".
         *   Anchors may be omitted only for 0 / $ addresses.
         */
        record DeleteRange(int begin, int end,
                           String beginAnchorLine, String beginAnchorContent,
                           String endAnchorLine, String endAnchorContent) implements EdCommand {}
    }

    public record ParseResult(List<OutputPart> parts, @Nullable String parseError) {
        public boolean hasEdits() {
            for (var p : parts) {
                if (!(p instanceof OutputPart.Text)) {
                    return true;
                }
            }
            return false;
        }
    }

    // NEW: helper to detect/parse @N| ... anchors; returns the addr or null
    private static @Nullable String matchAnchorAddr(String line) {
        var m = ANCHOR_LINE.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * After reading the required anchor(s) for a command, check if the very next line is an
     * unexpected extra anchor for this operation. We fail fast on the first extra anchor.
     *
     * We intentionally do not consume any lines here:
     *  - If the next line is not an anchor, we return startIndex unchanged.
     *  - If it is an anchor but not an "extra" for this op (likely belongs to the next stmt/body),
     *    we also return startIndex unchanged.
     *  - If it is an extra anchor for this op, we throw via fail(...).
     *
     * Special-case: if this is a single-address change/delete and there is exactly one extra
     * anchor (i.e., two anchors total were provided), produce a different message suggesting
     * they may have intended to use a two-address range.
     */
    private static void checkForExtraAnchorsForRange(
            String[] lines, int startIndex,
            String opName, String path, boolean isSingleAddress, List<String> errors) throws ParseAbort {

        if (startIndex >= lines.length) return;

        boolean nextIsAnchor = matchAnchorAddr(lines[startIndex]) != null;
        if (!nextIsAnchor) {
            // we have the correct number of anchors
            return;
        }

        // If single-address change/delete and exactly one extra anchor present,
        // provide a hint about using a two-address range.
        if (isSingleAddress && ("change".equals(opName) || "delete".equals(opName))) {
            boolean additionalExtraAnchors =
                    (startIndex + 1 < lines.length) && matchAnchorAddr(lines[startIndex + 1]) != null;
            if (!additionalExtraAnchors) {
                var opChar = "change".equals(opName) ? "c" : "d";
                var msg = """
                        Too many anchors after %s command for %s. You provided two anchors for a single-address edit.
                        
                        If you intended to edit a range, use a two-address form like:
                        ```
                        n,m %s
                        @n| ...
                        @m| ...
                        ```
                        
                        The first unexpected extra anchor was
                        ```
                        %s
                        ```
                        """.formatted(opName, path, opChar, lines[startIndex]);
                errors.add(msg);
                throw new ParseAbort(msg);
            }
        }

        // Generic message (current behavior)
        var msg = """
                Too many anchors after %s command for %s. Only specify one anchor per address.
                
                The first extra anchor was
                ```
                %s
                ```
                """.formatted(opName, path, lines[startIndex]);
        errors.add(msg);
        throw new ParseAbort(msg);
    }

    /**
     * Parse mixed content containing BRK_EDIT_EX / BRK_EDIT_RM blocks and plain text.
     * ED semantics:
     * - Fences are exact uppercase keywords on their own line: "BRK_EDIT_EX <path>", "BRK_EDIT_EX_END", "BRK_EDIT_RM <path>"
     * - <path> is "rest of the line after the first space", trimmed; may include spaces.
     * - Supported commands inside ED blocks:
     *     n a     (append after n)       body until a single '.' line; last body in a block may omit '.'
     *     n[,m] c (change range)         body as above
     *     n[,m] d (delete range)         no body
     * - All addresses are absolute numeric (1-based; 0 allowed only as "before first line"; '$' as end-of-file sentinel).
     * - 'w', 'q' and other non-edit commands are ignored. Shell ('!') is ignored but noted as a parse warning.
     */
    public ParseResult parse(String content) {
        var edits = new ArrayList<LineEdit>();
        var failures = new ArrayList<ParseFailure>();
        @Nullable ParseError fatalError = null;

        var lines = content.split("\n", -1);
        int i = 0;

        // Build snippet as "full edit up to the error (commands/anchors plus a bounded body sample)"
        StringBuilder blockSnippet = null;
        final int MAX_BODY_LINES_IN_SNIPPET = 10;

        // per-block accumulation
        var pendingBlockEdits = new ArrayList<LineEdit>();

        Runnable resetBlockState = () -> {
            blockSnippet = null;
            pendingBlockEdits.clear();
        };
        resetBlockState.run();

        // Failure helper
        java.util.function.BiConsumer<ParseFailureReason, String> recordFailure =
                (reason, message) -> failures.add(new ParseFailure(
                        reason,
                        message,
                        blockSnippet == null ? "" : blockSnippet.toString()));

        try {
            while (i < lines.length) {
                var raw = lines[i];
                var trimmed = raw.trim();

                // --- Deletes -----------------------------------------------------
                if (trimmed.startsWith("BRK_EDIT_RM")) {
                    int sp = trimmed.indexOf(' ');
                    if (sp < 0) {
                        recordFailure.accept(ParseFailureReason.MISSING_FILENAME, "BRK_EDIT_RM missing filename.");
                        i++;
                        continue;
                    }
                    var path = trimmed.substring(sp + 1).trim();
                    edits.add(new LineEdit.DeleteFile(path));
                    i++;
                    continue;
                }

                // --- Edit blocks -------------------------------------------------
                if (trimmed.equals("BRK_EDIT_EX") || trimmed.startsWith("BRK_EDIT_EX ")) {
                    int sp = trimmed.indexOf(' ');
                    if (sp < 0) {
                        recordFailure.accept(ParseFailureReason.MISSING_FILENAME, "BRK_EDIT_EX missing filename.");
                        i++;
                        continue;
                    }
                    var path = trimmed.substring(sp + 1).trim();

                    // Start block snippet with the opener line
                    blockSnippet = new StringBuilder();
                    blockSnippet.append(lines[i]).append('\n');
                    pendingBlockEdits.clear();
                    i++; // consume opener

                    boolean sawEndFence = false;

                    while (i < lines.length) {
                        var line = lines[i];
                        var t = line.trim();

                        if (t.equals(END_FENCE)) {
                            sawEndFence = true;
                            i++; // consume END
                            break;
                        }

                        var mRange = RANGE_CMD.matcher(t);
                        var mIA = IA_CMD.matcher(t);

                        // ---- Range commands: n[,m] c|d
                        if (mRange.matches()) {
                            var addr1 = mRange.group(1);
                            var addr2 = mRange.group(2);
                            char op = Character.toLowerCase(mRange.group(3).charAt(0));

                            int n1 = parseAddr(addr1);
                            int n2 = (addr2 == null) ? n1 : parseAddr(addr2);

                            blockSnippet.append(line).append('\n');
                            i++; // consume command line

                            try {
                                // Anchors
                                var a1 = readAnchorLine(lines, i,
                                                        "Malformed or missing first anchor after " + ((op == 'd') ? "delete" : "change") + " command: ");
                                i = a1.nextIndex();

                                String beginAnchorLine = a1.addr();
                                String beginAnchorContent = a1.content();

                                String endAnchorLine = "";
                                String endAnchorContent = "";

                                if (addr2 != null) {
                                    var a2 = readAnchorLine(lines, i,
                                                            "Malformed or missing second anchor after " + ((op == 'd') ? "delete" : "change") + " command: ");
                                    i = a2.nextIndex();
                                    endAnchorLine = a2.addr();
                                    endAnchorContent = a2.content();
                                } else if (op == 'c') {
                                    // single-line change uses same anchor for end
                                    endAnchorLine = beginAnchorLine;
                                    endAnchorContent = beginAnchorContent;
                                }

                                // Disallow extra anchors for same op
                                checkForExtraAnchorsForSameOp(lines, i);

                                if (op == 'd') {
                                    // delete (no body)
                                    int begin = (n1 < 1) ? 1 : n1;
                                    int end = n2;
                                    var beginAnchor = new LineEdit.Anchor(beginAnchorLine, beginAnchorContent);
                                    LineEdit.Anchor endAnchor = endAnchorLine.isBlank() ? null
                                            : new LineEdit.Anchor(endAnchorLine, endAnchorContent);

                                    pendingBlockEdits.add(new LineEdit.EditFile(
                                            path, begin, end, "", beginAnchor, endAnchor));

                                    // Allow implicit block close at EOF or next opener
                                    while (i < lines.length && lines[i].trim().isEmpty()) i++;
                                    if (i >= lines.length) { sawEndFence = true; break; }
                                    var la = lines[i].trim();
                                    if (la.equals("BRK_EDIT_EX") || la.startsWith("BRK_EDIT_EX ")) {
                                        sawEndFence = true; break;
                                    }
                                    continue;
                                } else {
                                    // 'c' change requires body
                                    var bodyRes = readBody(lines, i, MAX_BODY_LINES_IN_SNIPPET);
                                    i = bodyRes.nextIndex();

                                    for (var b : bodyRes.snippetLines()) blockSnippet.append(b).append('\n');
                                    if (bodyRes.truncated()) blockSnippet.append("... (body truncated)\n");

                                    int begin = (n1 < 1) ? 1 : n1;
                                    int end = n2;
                                    var beginAnchor = new LineEdit.Anchor(beginAnchorLine, beginAnchorContent);
                                    LineEdit.Anchor endAnchor = endAnchorLine.isBlank() ? null
                                            : new LineEdit.Anchor(endAnchorLine, endAnchorContent);

                                    pendingBlockEdits.add(new LineEdit.EditFile(
                                            path, begin, end, String.join("\n", bodyRes.body()), beginAnchor, endAnchor));

                                    if (bodyRes.implicitClose()) {
                                        if (i < lines.length) i++; // consume END
                                        sawEndFence = true; break;
                                    }

                                    while (i < lines.length && lines[i].trim().isEmpty()) i++;

                                    if (bodyRes.explicitTerminator()) {
                                        if (i >= lines.length) { sawEndFence = true; break; }
                                        var la = lines[i].trim();
                                        if (la.equals("BRK_EDIT_EX") || la.startsWith("BRK_EDIT_EX ")) {
                                            sawEndFence = true; break;
                                        }
                                    }
                                    continue;
                                }
                            } catch (Abort e) {
                                // record failure and resync to END fence (no explicit RESYNC reason exposed)
                                recordFailure.accept(e.reason, e.getMessage());
                                while (i < lines.length && !lines[i].trim().equals(END_FENCE)) i++;
                                if (i < lines.length) i++; // consume END
                                sawEndFence = true;
                                break;
                            }
                        }

                        // ---- Append commands: n a
                        if (mIA.matches()) {
                            var addr = mIA.group(1);
                            int n = parseAddr(addr);

                            blockSnippet.append(line).append('\n');
                            i++; // move to (optional) anchor line

                            try {
                                var anchor = readAnchorLine(lines, i,
                                                            "Malformed or missing anchor line after append command: ");
                                i = anchor.nextIndex();

                                // Disallow any extra anchors
                                checkForExtraAnchorsForSameOp(lines, i);

                                var bodyRes = readBody(lines, i, MAX_BODY_LINES_IN_SNIPPET);
                                i = bodyRes.nextIndex();

                                for (var b : bodyRes.snippetLines()) blockSnippet.append(b).append('\n');
                                if (bodyRes.truncated()) blockSnippet.append("... (body truncated)\n");

                                int begin = (n == Integer.MAX_VALUE) ? Integer.MAX_VALUE : n + 1;
                                var beginAnchor = new LineEdit.Anchor(anchor.addr(), anchor.content());
                                pendingBlockEdits.add(new LineEdit.EditFile(
                                        path, begin, begin - 1, String.join("\n", bodyRes.body()), beginAnchor, null));

                                if (bodyRes.implicitClose()) {
                                    if (i < lines.length) i++; // consume END
                                    sawEndFence = true;
                                    break;
                                }

                                while (i < lines.length && lines[i].trim().isEmpty()) i++;

                                if (bodyRes.explicitTerminator()) {
                                    if (i >= lines.length) { sawEndFence = true; break; }
                                    var la = lines[i].trim();
                                    if (la.equals("BRK_EDIT_EX") || la.startsWith("BRK_EDIT_EX ")) {
                                        sawEndFence = true; break;
                                    }
                                }
                                continue;
                            } catch (Abort e) {
                                recordFailure.accept(e.reason, e.getMessage());
                                while (i < lines.length && !lines[i].trim().equals(END_FENCE)) i++;
                                if (i < lines.length) i++;
                                sawEndFence = true;
                                break;
                            }
                        }

                        // Unrecognized line inside a block: skip without being fussy
                        i++;
                    }

                    if (!sawEndFence) {
                        fatalError = (fatalError == null) ? ParseError.EOF_IN_BLOCK : fatalError;
                        recordFailure.accept(ParseFailureReason.MISSING_END_FENCE, "Missing BRK_EDIT_EX_END for " + path);
                    }

                    // commit per-block edits parsed before any failure in this block
                    edits.addAll(pendingBlockEdits);
                    resetBlockState.run();
                    continue;
                }

                // Plain text outside of edits is ignored by the parser.
                i++;
            }
        } catch (Abort fatal) {
            if (fatalError == null) fatalError = ParseError.EOF_IN_BODY;
            recordFailure.accept(fatal.reason, fatal.getMessage());
        }

        // Sort EditFile ops per path in descending line order, then append DeleteFile ops in original order
        var editFiles = new ArrayList<LineEdit.EditFile>();
        var deletes = new ArrayList<LineEdit.DeleteFile>();
        for (var e : edits) {
            if (e instanceof LineEdit.EditFile ef) editFiles.add(ef);
            else if (e instanceof LineEdit.DeleteFile df) deletes.add(df);
        }
        editFiles.sort((a, b) -> {
            int c = a.file().compareTo(b.file());
            if (c != 0) return c;
            c = Integer.compare(b.beginLine(), a.beginLine());
            if (c != 0) return c;
            return Integer.compare(b.endLine(), a.endLine());
        });

        var combined = new ArrayList<LineEdit>(editFiles.size() + deletes.size());
        combined.addAll(editFiles);
        combined.addAll(deletes);

        if (fatalError != null && logger.isDebugEnabled()) {
            logger.debug("LineEditorParser parse encountered fatal error: {}", fatalError);
        }
        return new ParseResult(List.copyOf(combined), List.copyOf(failures), fatalError);
    }

    private static final Pattern RANGE_CMD = Pattern.compile("(?i)^([0-9]+|\\$)\\s*(?:,\\s*([0-9]+|\\$))?\\s*([cd])$");
    private static final Pattern IA_CMD    = Pattern.compile("(?i)^([0-9]+|\\$)\\s*(a)$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\s*@([0-9]+|\\$)\\s*\\|\\s*(.*)$");
    private static final String END_FENCE = "BRK_EDIT_EX_END";

    public static String addrToString(int n) {
        return (n == Integer.MAX_VALUE) ? "$" : Integer.toString(n);
    }
    private static int parseAddr(String address) {
        return "$".equals(address) ? Integer.MAX_VALUE : Integer.parseInt(address);
    }

    /** Unchecked control flow with a reasoned failure. */
    private static final class Abort extends Exception {
        final ParseFailureReason reason;
        Abort(ParseFailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private record AnchorReadResult(String addr, String content, int nextIndex) {}

    private static AnchorReadResult readAnchorLine(String[] lines, int index, String malformedPrefix) throws Abort {
        if (index >= lines.length) {
            throw new Abort(ParseFailureReason.MISSING_ANCHOR, malformedPrefix + "<EOF>");
        }
        var line = lines[index];
        var m = ANCHOR_LINE.matcher(line);
        if (m.matches()) {
            return new AnchorReadResult(m.group(1), m.group(2), index + 1);
        }
        throw new Abort(ParseFailureReason.ANCHOR_SYNTAX, malformedPrefix + line);
    }

    /**
     * Ensures we don't have an unexpected extra @N| line for single-address commands or after the
     * expected two anchors for a range. If found, abort with TOO_MANY_ANCHORS.
     * We do not consume the offending line here.
     */
    private static void checkForExtraAnchorsForSameOp(String[] lines, int startIndex) throws Abort {
        if (startIndex >= lines.length) return;
        var next = lines[startIndex];
        if (ANCHOR_LINE.matcher(next).matches()) {
            var msg = """
              Too many anchors for this operation. Only specify one anchor per address.

              First extra anchor:
              %s
              """.stripIndent().formatted(next);
            throw new Abort(ParseFailureReason.TOO_MANY_ANCHORS, msg);
        }
    }

    /**
     * Body reader for 'a' and 'c' commands.
     * Returns the body and how it ended (explicit "." vs implicit END fence),
     * plus a bounded set of lines to include in the failure snippet (not the full body).
     */
    private static BodyReadResult readBody(String[] lines, int startIndex, int maxSnippetLines) {
        var body = new ArrayList<String>();
        var snippetLines = new ArrayList<String>();
        int i = startIndex;
        boolean implicitClose = false;
        boolean explicitTerminator = false;
        boolean truncated = false;

        while (i < lines.length) {
            var bodyLine = lines[i];
            var bodyTrim = bodyLine.trim();

            if (END_FENCE.equals(bodyTrim)) {
                implicitClose = true; // do not consume END fence here
                break;
            }
            if (".".equals(bodyTrim)) {
                explicitTerminator = true;
                i++; // consume '.'
                break;
            }
            // accumulate body, but cap snippet lines
            body.add(unescapeBodyLine(bodyLine));
            if (snippetLines.size() < maxSnippetLines) {
                snippetLines.add(bodyLine);
            } else {
                truncated = true;
            }
            i++;
        }
        return new BodyReadResult(body, i, implicitClose, explicitTerminator, snippetLines, truncated);
    }

    private record BodyReadResult(
            List<String> body,
            int nextIndex,
            boolean implicitClose,
            boolean explicitTerminator,
            List<String> snippetLines,
            boolean truncated) {}

    private static String unescapeBodyLine(String line) {
        if (line.equals("\\.")) return ".";
        if (line.equals("\\\\")) return "\\";
        return line;
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    /**
     * Convert parsed ED parts into concrete LineEdit instances.
     * - ED blocks expand to one or more LineEdit.EditFile operations.
     * - BRK_EDIT_RM expands to LineEdit.DeleteFile.
     * - We sort all EditFile operations per file in descending line order (last edits first)
     *   to avoid line shifts; DeleteFile operations retain original order.
     */
    public static List<LineEdit> materializeEdits(ParseResult result, IContextManager cm) {
        var editFiles = new ArrayList<LineEdit>();
        var deletes = new ArrayList<LineEdit>();

        for (var part : result.parts()) {
            if (part instanceof OutputPart.Delete del) {
                var pf = cm.toFile(del.path());
                deletes.add(new LineEdit.DeleteFile(pf));
            } else if (part instanceof OutputPart.EdBlock ed) {
                var pf = cm.toFile(ed.path());
                for (var cmd : ed.commands()) {
                    if (cmd instanceof EdCommand.AppendAfter aa) {
                        int begin = (aa.line() == Integer.MAX_VALUE) ? Integer.MAX_VALUE : aa.line() + 1;
                        String body = String.join("\n", aa.body());
                        var beginAnchor = new LineEdit.Anchor(aa.anchorLine(), aa.anchorContent());
                        editFiles.add(new LineEdit.EditFile(pf, begin, begin - 1, body, beginAnchor, null));
                    } else if (cmd instanceof EdCommand.ChangeRange cr) {
                        int begin = (cr.begin() < 1) ? 1 : cr.begin();
                        int end = cr.end();
                        String body = String.join("\n", cr.body());
                        var beginAnchor = new LineEdit.Anchor(cr.beginAnchorLine(), cr.beginAnchorContent());
                        LineEdit.Anchor endAnchor = cr.endAnchorLine().isBlank()
                                ? null
                                : new LineEdit.Anchor(requireNonNull(cr.endAnchorLine()), requireNonNull(cr.endAnchorContent()));
                        editFiles.add(new LineEdit.EditFile(pf, begin, end, body, beginAnchor, endAnchor));
                    } else if (cmd instanceof EdCommand.DeleteRange dr) {
                        int begin = (dr.begin() < 1) ? 1 : dr.begin();
                        int end = dr.end();
                        var beginAnchor = new LineEdit.Anchor(dr.beginAnchorLine(), dr.beginAnchorContent());
                        LineEdit.Anchor endAnchor = dr.endAnchorLine().isBlank()
                                ? null
                                : new LineEdit.Anchor(requireNonNull(dr.endAnchorLine()), requireNonNull(dr.endAnchorContent()));
                        editFiles.add(new LineEdit.EditFile(pf, begin, end, "", beginAnchor, endAnchor));
                    }
                }
            }
        }

        editFiles.sort((x, y) -> {
            var a = (LineEdit.EditFile) x;
            var b = (LineEdit.EditFile) y;
            int c = a.file().toString().compareTo(b.file().toString());
            if (c != 0) return c;
            c = Integer.compare(b.beginLine(), a.beginLine());
            if (c != 0) return c;
            return Integer.compare(b.endLine(), a.endLine());
        });

        var out = new ArrayList<LineEdit>(editFiles.size() + deletes.size());
        out.addAll(editFiles);
        out.addAll(deletes);
        return out;
    }

    // -----------------------------------------------------------------------------
    // Prompt surface (analogous to EditBlockParser's prompt API)
    // -----------------------------------------------------------------------------

    /**
     * Canonical rules for the ED-mode edit format.
     */
    public String lineEditFormatInstructions() {
        return """
There are two editing commands: BRK_EDIT_EX, and BRK_EDIT_RM.

# BRK_EDIT_RM
Usage:
BRK_EDIT_RM <full_path>

That's it, no closing fence is required.

# BRK_EDIT_EX
BRK_EDIT_EX is a line-oriented editing format that supports multiple edits per file.

Informal EBNF grammar:
ex_block ::= "BRK_EDIT_EX" SP path NL { stmt } "BRK_EDIT_EX_END" NL?
stmt     ::= append | change | delete
append   ::= n SP "a" NL anchor(n) body
change   ::= n ["," m] SP "c" NL anchor(n) [ anchor(m) ] body
delete   ::= n ["," m] SP "d" NL anchor(n) [ anchor(m) ]
anchor(x)::= "@" x "|" SP? text NL
body     ::= { body_line } ( "." NL | /* implicit before END */ )
body_line::= "\\." NL | "\\\\" NL | other NL
n,m      ::= addr ; addr ::= "0" | "$" | INT
INT     ::= "1".."9" { DIGIT } ; DIGIT ::= "0".."9"
SP      ::= (" " | "\\t")+ ; NL ::= "\\n"

To elaborate, supported edits are
a: append after given address
c: change given address or range
d: delete given address or range

Addresses:
- Single line (n) or inclusive range (n,m), 1-based integers.
- Special addresses: `0` (before first line) and `$` (after last line).

Anchors (MANDATORY):
- Provide exactly one anchor per address parameter:
  • Single-address commands (n a / n c / n d): exactly 1 anchor.
  • Range commands (n,m c / n,m d): exactly 2 anchors, for n and m.
- Anchors never define ranges. The addresses on the command line do.
  If you emit two `@N|` anchors, you MUST also emit a two-address form `n,m X`.
- For `0` and `$`, include a blank anchor after the pipe (e.g., `@0| `, `@$| `).
- Do NOT include anchors for interior lines of a range.
- Any additional anchor lines (`@N| ...`) beyond the required ones are a **parse error**.

Common mistakes & self-checks:
- If you wrote two `@N|` lines, your command must be `n,m c` or `n,m d` with those same N values.
- Count addresses on the command line; emit exactly that many anchors (no more, no fewer).
- Anchors are for verification of endpoints only; never anchor all lines in the middle of a range.

Wrong vs Right (missing ",m" on a range change):
WRONG:
1175 c
@1175| ...
@1184| ...
body...
.
RIGHT:
1175,1184 c
@1175| ...
@1184| ...
body...
.

Right vs Wrong (range change, interior anchors):
WRONG:
323,326 c
@323| line A
@324| line B
@325| line C
@326| line D
new body...
.
RIGHT:
323,326 c
@323| line A
@326| line D
new body...
.

Body rules:
- Only **a** and **c** have bodies. A body ends with a single dot `.` on its own line.
- To include a literal `.` or `\\` line in the body, use `\\.` and `\\\\` respectively.

Fences and paths:
- Fences are ALL CAPS and appear alone on their own lines.
- <full_path> is the remainder of the fence line after the first space, trimmed; it may include spaces.

Conventions and constraints:
- All addresses are absolute; ranges are inclusive; **n,m** are 1-based (with `0`/`$` as described).
- Do not overlap edits. Emit commands **last-edits-first** per file to avoid line shifts.
- To create a new file, use `BRK_EDIT_EX <path>` with `0 a` and the entire file body; include `@0| `.
- To remove a file, use `BRK_EDIT_RM <path>` on a single line (no END fence).
- `n,m c` ranges are INCLUSIVE; avoid off-by-one errors.
- ASCII only; no Markdown fences, diffs, or JSON in edit blocks.
""".stripIndent();
    }

    public List<ChatMessage> exampleMessages() {
        return List.of(
                // Example 1 — WORKSPACE goes in the user message
                new UserMessage("""
                Change get_factorial() to use math.factorial
                
                <workspace>
                <file path="mathweb/flask/app.py">
                1: from flask import Flask, request
                2: app = Flask(__name__)
                3:
                4: def index():
                5:     return "ok"
                6:
                7: # helpers
                8:
                9: def get_factorial(n):
                10:     if n < 0:
                11:         raise ValueError("negative")
                12:     if n == 0:
                13:         return 1
                14:     return n * get_factorial(n - 1)
                15:
                16: @app.route("/fact")
                17: def fact():
                18:     n = int(request.args.get("n", "0"))
                19:     if n < 0:
                20:         return "bad", 400
                21:     return str(get_factorial(n))
                22:
                23: if __name__ == "__main__":
                24:     app.run()
                </file>
                </workspace>
                """.stripIndent()),
                new AiMessage("""
              We will:
              1) Add the math import at the top of app.py
              2) Remove the recursive implementation
              3) Update the call site
              
              BRK_EDIT_EX mathweb/flask/app.py
              21 c
              @21|     return str(get_factorial(n))
                  return str(math.factorial(n))
              .
              9,14 d
              @9| def get_factorial(n):
              @14|     return n * get_factorial(n - 1)
              0 a
              @0|
              import math
              .
              BRK_EDIT_EX_END
              """.stripIndent()),

                // Example 2 — WORKSPACE goes in the user message; no filler or anachronistic comments
                new UserMessage("""
                Refactor hello() into its own file.
                
                <workspace>
                <file path="main.py">
                1: import sys
                2:
                3: def main():
                4:     hello()
                5:
                6: if __name__ == "__main__":
                7:     main()
                8:
                9: def hello():
                10:     \\"\\"\\"print a greeting\\"\\"\\"
                11:     print("hello")
                </file>
                </workspace>
                """.stripIndent()),
                new AiMessage("""
              We will:
              1) Create a new hello.py with hello()
              2) Import it in main.py and remove the old function
              3) Add a usage comment at the end of main.py
              
              BRK_EDIT_EX hello.py
              0 a
              @0|
              def hello():
                  \\"\\"\\"print a greeting\\"\\"\\"
                  print("hello")
              .
              BRK_EDIT_EX_END
              
              BRK_EDIT_EX main.py
              $ a
              @$|
              # Usage: hello()
              .
              9,11 d
              @9| def hello():
              @11|     print("hello")
              0 a
              @0|
              from hello import hello
              .
              BRK_EDIT_EX_END
              """.stripIndent()),
                new UserMessage("""
                Demonstrate a range change, anchored append (non-0/$), and a single-line delete on sample.txt.

                <workspace>
                <file path="sample.txt">
                1: one
                2: two
                3: three
                4: four
                5: five
                </file>
                </workspace>
                """.stripIndent()),
                new AiMessage("""
              We will:
              - Append a line after 3, anchored to current content of line 3
              - Replace the inclusive range 2..4
              - Delete line 5

              BRK_EDIT_EX sample.txt
              3 a
              @3| three
              after three
              .
              2,4 c
              @2| two
              @4| four
              TWO_TO_FOUR
              .
              5 d
              @5| five
              BRK_EDIT_EX_END
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
- Think first; then provide a brief, high-signal explanation of the changes (1-3 short bullets).
- After the explanation, output ONLY BRK_EDIT_EX / BRK_EDIT_RM blocks for the actual edits (no other formats).
- Always include the closing fence BRK_EDIT_EX_END for every BRK_EDIT_EX block.
- Prefer multiple commands inside a single BRK_EDIT_EX block per file instead of opening many blocks.
- Keep edits small and emit last-edits-first within each file. Avoid overlapping ranges entirely; combine into a single edit instead.
- Use `0 a` to insert at the start of a file, and `$ a` to append at the end.
- To create a new file, use `0 a` with the whole file body; do not attempt a replace on a missing file.
- `n,m c` ranges are INCLUSIVE, MAKE SURE YOU ARE NOT OFF BY ONE.
- **Anchor checklist (must pass before you emit):**
  • Count the addresses on the command line; emit exactly that many anchors.
  • Anchors NEVER define ranges—the command line does. If you emit two `@N|` anchors, your command MUST be `n,m X` with the same endpoints.
  • For ranges, anchor ONLY the first and last line, never interior lines.
  • Extra anchor lines are a parse error and will be rejected.
- ASCII only; no Markdown code fences, diffs, or JSON around the edit blocks.

%s
</rules>

<goal%s>
%s
</goal>
""".stripIndent().formatted(lineEditFormatInstructions(), reminder, targetAttr, input);
    }

    public static String repr(OutputPart part) {
        if (part instanceof OutputPart.Delete(String path)) {
            return "BRK_EDIT_RM " + path;
        }
        if (part instanceof OutputPart.EdBlock(String path, List<EdCommand> commands)) {
            var sb = new StringBuilder();
            sb.append("BRK_EDIT_EX ").append(path).append('\n');
            for (var c : commands) {
                if (c instanceof EdCommand.AppendAfter(
                        int line, List<String> body, String anchorLine, String anchorContent
                )) {
                    sb.append(addrToString(line)).append(" a\n");
                    sb.append("@").append(anchorLine).append("| ").append(anchorContent).append('\n');
                    LineEdit.renderBody(sb, body);
                } else if (c instanceof EdCommand.ChangeRange(
                        int begin, int end, List<String> body, String beginAnchorLine, String beginAnchorContent,
                        String endAnchorLine, String endAnchorContent
                )) {
                    sb.append(addrToString(begin)).append(',').append(addrToString(end)).append(" c\n");
                    sb.append("@").append(beginAnchorLine).append("| ").append(beginAnchorContent).append('\n');
                    if (!endAnchorLine.isBlank()) {
                        sb.append("@").append(endAnchorLine).append("| ").append(requireNonNull(endAnchorContent)).append('\n');
                    }
                    LineEdit.renderBody(sb, body);
                } else if (c instanceof EdCommand.DeleteRange(
                        int begin, int end, String beginAnchorLine, String beginAnchorContent, String endAnchorLine,
                        String endAnchorContent
                )) {
                    sb.append(addrToString(begin)).append(',').append(addrToString(end)).append(" d\n");
                    sb.append("@").append(beginAnchorLine).append("| ").append(beginAnchorContent).append('\n');
                    if (!endAnchorLine.isBlank()) {
                        sb.append("@").append(endAnchorLine).append("| ").append(requireNonNull(endAnchorContent)).append('\n');
                    }
                }
            }
            sb.append("BRK_EDIT_EX_END");
            return sb.toString();
        }
        if (part instanceof OutputPart.Text(String text)) {
            return text;
        }
        return part.toString();
    }

}
