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
import java.util.function.Function;
import java.util.regex.Matcher;
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
        record AppendAfter(int line, List<String> body, String anchorToken, String anchorContent) implements EdCommand {}

        /**
         * n[,m] c — replace inclusive range; body required; anchors:
         * - If only n is provided, exactly one anchor "n: content" (end anchor equals begin).
         * - If n,m are provided, two anchors "n: content" and "m: content".
         *   Anchors may be omitted only for 0 / $ addresses.
         */
        record ChangeRange(int begin, int end, List<String> body,
                           String beginAnchorToken, String beginAnchorContent,
                           String endAnchorToken, String endAnchorContent) implements EdCommand {}

        /**
         * n[,m] d — delete inclusive range; anchors:
         * - If only n is provided, exactly one anchor "n: content".
         * - If n,m are provided, two anchors "n: content" and "m: content".
         *   Anchors may be omitted only for 0 / $ addresses.
         */
        record DeleteRange(int begin, int end,
                           String beginAnchorToken, String beginAnchorContent,
                           String endAnchorToken, String endAnchorContent) implements EdCommand {}
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
    public ExtendedParseResult parse(String content) {
        // Minimal guard for legacy typed-tag inputs (e.g., <brk_edit_file ...> ... </brk_edit_file>)
        var lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("<brk_edit_file")) {
            if (!lower.contains("</brk_edit_file>")) {
                var onlyText = new ArrayList<OutputPart>();
                onlyText.add(new OutputPart.Text(content));
                return new ExtendedParseResult(onlyText, "Missing closing </brk_edit_file> tag");
            }
            var onlyText = new ArrayList<OutputPart>();
            onlyText.add(new OutputPart.Text(content));
            return new ExtendedParseResult(
                    onlyText,
                    "Typed <brk_edit_file> blocks are not supported by ED parser; use BRK_EDIT_EX / BRK_EDIT_EX_END.");
        }

        var parts = new ArrayList<OutputPart>();
        var errors = new ArrayList<String>();

        var lines = content.split("\n", -1);
        int i = 0;

        var textBuf = new StringBuilder();

        // Helpers
        Runnable flushText = () -> {
            if (!textBuf.isEmpty()) {
                parts.add(new OutputPart.Text(textBuf.toString()));
                textBuf.setLength(0);
            }
        };
        java.util.function.Predicate<String> isZeroOrDollar =
                tok -> "0".equals(tok) || "$".equals(tok);

        while (i < lines.length) {
            var line = lines[i];
            var trimmed = line.trim();

            // BRK_EDIT_RM <path>
            if (line.startsWith("BRK_EDIT_RM")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    textBuf.append(line);
                    if (i < lines.length - 1) textBuf.append('\n');
                    errors.add("BRK_EDIT_RM missing filename.");
                    i++;
                    continue;
                }
                var path = trimmed.substring(sp + 1).trim();
                flushText.run();
                parts.add(new OutputPart.Delete(path));
                i++;
                continue;
            }

            // BRK_EDIT_EX <path>
            if (line.startsWith("BRK_EDIT_EX")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    textBuf.append(line);
                    if (i < lines.length - 1) textBuf.append('\n');
                    errors.add("BRK_EDIT_EX missing filename.");
                    i++;
                    continue;
                }
                var path = trimmed.substring(sp + 1).trim();

                flushText.run();

                int blockStartIndex = i;
                i++;
                var commands = new ArrayList<EdCommand>();
                boolean sawEndFence = false;

                while (i < lines.length) {
                    var cmdLine = lines[i];
                    var cmdTrim = cmdLine.trim();

                    if (cmdLine.equals("BRK_EDIT_EX_END")) {
                        sawEndFence = true;
                        i++;
                        break;
                    }

                    if (cmdTrim.isEmpty()
                            || cmdTrim.equals("w") || cmdTrim.equals("q")
                            || cmdTrim.equals("wq") || cmdTrim.equals("qw")) {
                        i++;
                        continue;
                    }

                    if (cmdTrim.startsWith("!")) {
                        errors.add("Shell command ignored inside BRK_EDIT_EX: " + cmdTrim);
                        i++;
                        continue;
                    }

                    var range = RANGE_CMD.matcher(cmdTrim);
                    var ia = IA_CMD.matcher(cmdTrim);

                    if (range.matches()) {
                        var tok1 = range.group(1);
                        var tok2 = range.group(2);
                        int n1 = parseAddr(tok1);
                        int n2 = (tok2 == null) ? n1 : parseAddr(tok2);
                        char op = Character.toLowerCase(range.group(3).charAt(0));

                        if (op == 'd') {
                            // Delete: expect 1 anchor for single-address; 2 anchors for range.
                            i++;

                            // Begin anchor
                            var beginTok = tok1;
                            var beginAnchorContent = "";
                            if (i < lines.length) {
                                var a1Line = lines[i];
                                var a1m = ANCHOR_LINE.matcher(a1Line);
                                if (a1m.matches()) {
                                    var parsedTok = a1m.group(1);
                                    var parsedContent = a1m.group(2);
                                    if (!parsedTok.equals(beginTok)) {
                                        errors.add("Delete begin anchor token '" + parsedTok
                                                           + "' does not match address '" + beginTok + "'");
                                    } else {
                                        beginAnchorContent = parsedContent;
                                    }
                                    i++; // consumed begin anchor
                                } else if (!isZeroOrDollar.test(beginTok)) {
                                    errors.add("Malformed or missing first anchor after delete command: " + a1Line);
                                    i++; // consume questionable line to avoid infinite loop
                                }
                            } else if (!isZeroOrDollar.test(beginTok)) {
                                errors.add("Missing anchor line(s) after delete command for " + path);
                            }

                            // Optional end anchor (required if tok2 is present and not 0/$)
                            String endTok;
                            String endAnchorContent;
                            if (tok2 != null) {
                                endTok = tok2;
                                endAnchorContent = "";
                                if (i < lines.length) {
                                    var a2Line = lines[i];
                                    var a2m = ANCHOR_LINE.matcher(a2Line);
                                    if (a2m.matches()) {
                                        var parsedTok2 = a2m.group(1);
                                        var parsedContent2 = a2m.group(2);
                                        if (!parsedTok2.equals(endTok)) {
                                            errors.add("Delete end anchor token '" + parsedTok2
                                                               + "' does not match address '" + endTok + "'");
                                        } else {
                                            endAnchorContent = parsedContent2;
                                        }
                                        i++; // consumed end anchor
                                    } else if (!isZeroOrDollar.test(endTok)) {
                                        errors.add("Malformed or missing second anchor after delete command: " + a2Line);
                                        i++; // consume questionable line
                                    }
                                } else if (!isZeroOrDollar.test(endTok)) {
                                    errors.add("Missing second anchor line for range delete command for " + path);
                                }
                            } else {
                                endTok = "";
                                endAnchorContent = "";
                            }

                            commands.add(new EdCommand.DeleteRange(
                                    n1, n2, beginTok, beginAnchorContent, endTok, endAnchorContent));
                            continue;
                        } else { // 'c'
                            i++;

                            // Begin anchor: required unless 0 or $
                            var beginTok = tok1;
                            var beginAnchorContent = "";
                            if (i < lines.length) {
                                var a1Line = lines[i];
                                var a1m = ANCHOR_LINE.matcher(a1Line);
                                if (a1m.matches()) {
                                    var parsedTok = a1m.group(1);
                                    var parsedContent = a1m.group(2);
                                    if (!parsedTok.equals(beginTok)) {
                                        errors.add("Change begin anchor token '" + parsedTok
                                                           + "' does not match address '" + beginTok + "'");
                                    } else {
                                        beginAnchorContent = parsedContent;
                                    }
                                    i++; // consumed begin anchor
                                } else if (!isZeroOrDollar.test(beginTok)) {
                                    errors.add("Malformed or missing first anchor after change command: " + a1Line);
                                    i++; // consume questionable line
                                }
                            } else if (!isZeroOrDollar.test(beginTok)) {
                                errors.add("Missing anchor line(s) after change command for " + path);
                            }

                            // End token & anchor
                            String endTok;
                            String endAnchorContent;
                            if (tok2 != null) {
                                endTok = tok2;
                                endAnchorContent = "";
                                if (i < lines.length) {
                                    var a2Line = lines[i];
                                    var a2m = ANCHOR_LINE.matcher(a2Line);
                                    if (a2m.matches()) {
                                        var parsedTok2 = a2m.group(1);
                                        var parsedContent2 = a2m.group(2);
                                        if (!parsedTok2.equals(endTok)) {
                                            errors.add("Change end anchor token '" + parsedTok2
                                                               + "' does not match address '" + endTok + "'");
                                        } else {
                                            endAnchorContent = parsedContent2;
                                        }
                                        i++; // consumed end anchor
                                    } else if (!isZeroOrDollar.test(endTok)) {
                                        errors.add("Malformed or missing second anchor after change command: " + a2Line);
                                        i++; // consume questionable line
                                    }
                                } else if (!isZeroOrDollar.test(endTok)) {
                                    errors.add("Missing second anchor line for range change command for " + path);
                                }
                            } else {
                                // Single-line change: end anchor equals begin
                                endTok = beginTok;
                                endAnchorContent = beginAnchorContent;
                            }

                            // Body until '.' or implicit end
                            var body = new ArrayList<String>();
                            boolean implicitClose = false;
                            while (i < lines.length) {
                                var bodyLine = lines[i];
                                var bodyTrim = bodyLine.trim();
                                if (bodyTrim.equals("BRK_EDIT_EX_END")) {
                                    implicitClose = true;
                                    sawEndFence = true;
                                    break;
                                }
                                if (bodyTrim.equals(".")) {
                                    i++;
                                    break;
                                }
                                body.add(unescapeBodyLine(bodyLine));
                                i++;
                            }
                            commands.add(new EdCommand.ChangeRange(
                                    n1, n2, body, beginTok, beginAnchorContent, endTok, endAnchorContent));
                            if (implicitClose) {
                                // outer loop will see END next
                            }
                            continue;
                        }
                    }

                    if (ia.matches()) {
                        var addr = ia.group(1);
                        int n = parseAddr(addr);

                        // Anchor for append-after: required unless 0 or $
                        i++;
                        var aTok = addr;
                        var aContent = "";
                        if (i < lines.length) {
                            var anchorLine = lines[i];
                            var am = ANCHOR_LINE.matcher(anchorLine);
                            if (am.matches()) {
                                var parsedTok = am.group(1);
                                var parsedContent = am.group(2);
                                if (!parsedTok.equals(aTok)) {
                                    errors.add("Append anchor token '" + parsedTok
                                                       + "' does not match address '" + aTok + "'");
                                } else {
                                    aContent = parsedContent;
                                }
                                i++; // consumed anchor
                            } else if (!isZeroOrDollar.test(aTok)) {
                                errors.add("Malformed or missing anchor line after append command: " + anchorLine);
                                i++; // consume to avoid infinite loop
                            }
                        } else if (!isZeroOrDollar.test(aTok)) {
                            errors.add("Missing anchor line after append command for " + path);
                        }

                        var body = new ArrayList<String>();
                        boolean implicitClose = false;
                        while (i < lines.length) {
                            var bodyLine = lines[i];
                            var bodyTrim = bodyLine.trim();
                            if (bodyTrim.equals("BRK_EDIT_EX_END")) {
                                implicitClose = true;
                                sawEndFence = true;
                                break;
                            }
                            if (bodyTrim.equals(".")) {
                                i++;
                                break;
                            }
                            body.add(unescapeBodyLine(bodyLine));
                            i++;
                        }
                        commands.add(new EdCommand.AppendAfter(n, body, aTok, aContent));
                        if (implicitClose) {
                            // outer loop will see END next
                        }
                        continue;
                    }

                    // Unrecognized line inside ED block: ignore silently
                    i++;
                }

                if (!sawEndFence) {
                    var sb = new StringBuilder();
                    sb.append("BRK_EDIT_EX ").append(path).append('\n');
                    for (int k = blockStartIndex + 1; k < lines.length; k++) {
                        sb.append(lines[k]);
                        if (k < lines.length - 1) sb.append('\n');
                    }
                    parts.add(new OutputPart.Text(sb.toString()));
                    errors.add("Missing BRK_EDIT_EX_END for " + path);
                    break;
                }

                parts.add(new OutputPart.EdBlock(path, List.copyOf(commands)));
                continue;
            }

            textBuf.append(line);
            if (i < lines.length - 1) textBuf.append('\n');
            i++;
        }

        if (textBuf.length() > 0) {
            parts.add(new OutputPart.Text(textBuf.toString()));
        }

        var error = errors.isEmpty() ? null : String.join("\n", errors);
        if (error != null && logger.isDebugEnabled()) {
            logger.debug("LineEditorParser (ED mode) parse warnings:\n{}", error);
        }
        return new ExtendedParseResult(parts, error);
    }

    // Patterns for EX commands
    private static final Pattern RANGE_CMD = Pattern.compile("(?i)^([0-9]+|\\$)\\s*(?:,\\s*([0-9]+|\\$))?\\s*([cd])$");
    private static final Pattern IA_CMD    = Pattern.compile("(?i)^([0-9]+|\\$)\\s*(a)$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^([0-9]+|\\$)\\s*:\\s*(.*)$");

    private static int parseAddr(String token) {
        return "$".equals(token) ? Integer.MAX_VALUE : Integer.parseInt(token);
    }

    private static String addrToString(int n) {
        return (n == Integer.MAX_VALUE) ? "$" : Integer.toString(n);
    }

    // Minimal unescape for body lines: "\." => ".", "\\" => "\"
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
    public static List<LineEdit> materializeEdits(ExtendedParseResult result, IContextManager cm) {
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
                        var beginAnchor = new LineEdit.Anchor(aa.anchorToken(), aa.anchorContent());
                        editFiles.add(new LineEdit.EditFile(pf, begin, begin - 1, body, beginAnchor, null));
                    } else if (cmd instanceof EdCommand.ChangeRange cr) {
                        int begin = (cr.begin() < 1) ? 1 : cr.begin();
                        int end = cr.end();
                        String body = String.join("\n", cr.body());
                        var beginAnchor = new LineEdit.Anchor(cr.beginAnchorToken(), cr.beginAnchorContent());
                        @Nullable LineEdit.Anchor endAnchor = cr.endAnchorToken().isBlank()
                                ? null
                                : new LineEdit.Anchor(requireNonNull(cr.endAnchorToken()), requireNonNull(cr.endAnchorContent()));
                        editFiles.add(new LineEdit.EditFile(pf, begin, end, body, beginAnchor, endAnchor));
                    } else if (cmd instanceof EdCommand.DeleteRange dr) {
                        int begin = (dr.begin() < 1) ? 1 : dr.begin();
                        int end = dr.end();
                        var beginAnchor = new LineEdit.Anchor(dr.beginAnchorToken(), dr.beginAnchorContent());
                        @Nullable LineEdit.Anchor endAnchor = dr.endAnchorToken().isBlank()
                                ? null
                                : new LineEdit.Anchor(requireNonNull(dr.endAnchorToken()), requireNonNull(dr.endAnchorContent()));
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

# BRK_EDIT_EX
BRK_EDIT_EX is a line-oriented editing format that supports multiple edits per file.
Each edit has the form:
 [address] [command]
 [anchors, if necessary]
 [body, if necessary]

Edits are parsed until the mandatory BRK_EDIT_EX_END fence is encountered.

Supported edits are
a: append after given address
c: change given address or range
d: delete given address or range

An address may be a single line number, or a range denoted with commas. Additionally, two special addresses are supported:
- `0`  -> the very start of the file (before the first line)
- `$`  -> the end of the file (after the last line)

Anchors are the current content at an address line, used to validate that the edit is applied to the expected version.
Provide one anchor per address parameter (except you may omit anchors for `0` and `$`).

Body rules:
- Only **a** and **c** have bodies. The body ends with a single dot `.` on its own line.
- To include a literal `.` or `\\` line in the body, use `\\.` and `\\\\` respectively (anchors are unescaped).

Emit edits using ONLY these fences (ALL CAPS, each on its own line; no Markdown fences):

So, BRK_EDIT_EX commands will have some combination of these edits:

BRK_EDIT_EX <full_path>
<n> a
<n>: <current content at n (omit allowed for 0/$)>
...body...
.
<n>[,<m>] c
<n>: <current content at n (omit allowed for 0)>
<m>: <current content at m (omit allowed for $)>    # required only when m is present
...body...
.
<n>[,<m>] d
<n>: <current content at n (omit allowed for 0/$)>
<m>: <current content at m (omit allowed for $)>    # required only when m is present
BRK_EDIT_EX_END

Path rules:
- <full_path> is the remainder of the fence line after the first space, trimmed; it may include spaces.

Conventions and constraints:
- All addresses are absolute and inclusive for ranges; **n,m** are 1-based integers (with `0` and `$` as described).
- Do not overlap edits. Emit commands **last-edits-first** within each file to avoid line shifts.
- To create a new file, use `BRK_EDIT_EX <path>` with `0 a` and the entire file body; you may omit the `0:` anchor.
- To remove a file, use `BRK_EDIT_RM <path>` on a single line (no END fence).
- `n,m c` ranges are INCLUSIVE, MAKE SURE YOU ARE NOT OFF BY ONE.
- ASCII only; no Markdown code fences, diffs, or JSON around the edit blocks.
- Non-command, non-anchor, non-body lines inside a BRK_EDIT_EX block will be ignored by the parser; do not include commentary inside the block.

## Quick cheat-sheet

BRK_EDIT_EX <path>
<n> a
<n>: <line n>                     # omit anchor only for 0/$
...body...
.

<n>[,<m>] c
<n>: <line n>                     # omit anchor only for 0
<m>: <line m>                     # when present; omit only for $
...body...
.

<n>[,<m>] d
<n>: <line n>                     # omit anchor only for 0/$
<m>: <line m>                     # when present; omit only for $
BRK_EDIT_EX_END

## EBNF (informal)
address    := "0" | "$" | INT
range      := address [ "," address ]
cmd        := range ("a" | "c" | "d")
anchor     := address ":" TEXT
body       := (LINE | "\\." | "\\\\")* "."   # final body in a block may omit the terminating "."

## Examples (no Markdown fences)

# Append one line at end-of-file (anchor omitted for '$')
BRK_EDIT_EX src/main.py
$ a
print("done")
.
BRK_EDIT_EX_END

# Insert two lines at the start of the file (anchor omitted for '0')
BRK_EDIT_EX README.md
0 a
# Project Title
A short description.
.
BRK_EDIT_EX_END

# Replace a single line (line 21; anchors required)
BRK_EDIT_EX src/Main.java
21 c
21: return str(get_factorial(n))
return str(math.factorial(n))
.
BRK_EDIT_EX_END

# Delete a range with two anchors (omit allowed only for 0/$)
BRK_EDIT_EX app.py
9,14 d
9: def get_factorial(n):
14:     return n * get_factorial(n - 1)
BRK_EDIT_EX_END
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
                  21:     return str(get_factorial(n))
                      return str(math.factorial(n))
                  .
                  9,14 d
                  9: def get_factorial(n):
                  14: return n * get_factorial(n - 1)
                  0 a
                  0: from flask import Flask, request
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
                    10:     \"\"\"print a greeting\"\"\"
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
                  def hello():
                      \"\"\"print a greeting\"\"\"
                      print("hello")
                  .
                  BRK_EDIT_EX_END
                  
                  BRK_EDIT_EX main.py
                  $ a
                  $:     print("hello")
                  # Usage: hello()
                  .
                  9,11 d
                  9: def hello():
                  11:     print("hello")
                  0 a
                  0: import sys
                  from hello import hello
                  .
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
- Keep edits small and **emit last-edits-first** within each file. Avoid overlapping ranges entirely.
- Use `0 a` to insert at the start of a file, and `$ a` to append at the end.
- To create a new file, use `0 a` with the whole file body; do not attempt a replace on a missing file.
- `n,m c` ranges are INCLUSIVE, MAKE SURE YOU ARE NOT OFF BY ONE.
- ASCII only; no Markdown code fences, diffs, or JSON around the edit blocks.

%s
</rules>

<goal%s>
%s
</goal>
""".stripIndent().formatted(lineEditFormatInstructions(), reminder, targetAttr, input);
    }

    public static String repr(OutputPart part) {
        if (part instanceof OutputPart.Delete del) {
            return "BRK_EDIT_RM " + del.path();
        }
        if (part instanceof OutputPart.EdBlock ed) {
            var sb = new StringBuilder();
            sb.append("BRK_EDIT_EX ").append(ed.path()).append('\n');
            for (var c : ed.commands()) {
                if (c instanceof EdCommand.AppendAfter aa) {
                    sb.append(addrToString(aa.line())).append(" a\n");
                    sb.append(aa.anchorToken()).append(": ").append(aa.anchorContent()).append('\n');
                    renderBody(sb, aa.body());
                } else if (c instanceof EdCommand.ChangeRange cr) {
                    sb.append(addrToString(cr.begin())).append(',').append(addrToString(cr.end())).append(" c\n");
                    sb.append(cr.beginAnchorToken()).append(": ").append(cr.beginAnchorContent()).append('\n');
                    if (!cr.endAnchorToken().isBlank()) {
                        sb.append(cr.endAnchorToken()).append(": ").append(requireNonNull(cr.endAnchorContent())).append('\n');
                    }
                    renderBody(sb, cr.body());
                } else if (c instanceof EdCommand.DeleteRange dr) {
                    sb.append(addrToString(dr.begin())).append(',').append(addrToString(dr.end())).append(" d\n");
                    sb.append(dr.beginAnchorToken()).append(": ").append(dr.beginAnchorContent()).append('\n');
                    if (!dr.endAnchorToken().isBlank()) {
                        sb.append(dr.endAnchorToken()).append(": ").append(requireNonNull(dr.endAnchorContent())).append('\n');
                    }
                }
            }
            sb.append("BRK_EDIT_EX_END");
            return sb.toString();
        }
        if (part instanceof OutputPart.Text txt) {
            return txt.text();
        }
        return part.toString();
    }

    public static String repr(LineEdit edit) {
        if (edit instanceof LineEdit.DeleteFile df) {
            return "BRK_EDIT_RM " + canonicalPath(df.file());
        }
        if (edit instanceof LineEdit.EditFile ef) {
            var sb = new StringBuilder();
            sb.append("BRK_EDIT_EX ").append(canonicalPath(ef.file())).append('\n');
            int beginLine = ef.beginLine();
            int endLine = ef.endLine();
            String content = ef.content();

            if (endLine < beginLine) {
                // insertion
                String addr = (beginLine == Integer.MAX_VALUE) ? "$" : addrToString(beginLine - 1);
                sb.append(addr).append(" a\n");
                if (ef.beginAnchor() != null) {
                    sb.append(ef.beginAnchor().addrToken()).append(": ").append(ef.beginAnchor().content()).append('\n');
                }
                renderBody(sb, content.isEmpty() ? List.of() : content.lines().toList());
            } else if (content.isEmpty()) {
                // delete
                sb.append(addrToString(beginLine)).append(',').append(addrToString(endLine)).append(" d\n");
                if (ef.beginAnchor() != null) {
                    sb.append(ef.beginAnchor().addrToken()).append(": ").append(ef.beginAnchor().content()).append('\n');
                }
            } else {
                // change
                sb.append(addrToString(beginLine)).append(',').append(addrToString(endLine)).append(" c\n");
                if (ef.beginAnchor() != null) {
                    sb.append(ef.beginAnchor().addrToken()).append(": ").append(ef.beginAnchor().content()).append('\n');
                }
                if (ef.endAnchor() != null) {
                    sb.append(ef.endAnchor().addrToken()).append(": ").append(ef.endAnchor().content()).append('\n');
                }
                renderBody(sb, content.lines().toList());
            }
            sb.append("BRK_EDIT_EX_END");
            return sb.toString();
        }
        return edit.toString();
    }

    private static void renderBody(StringBuilder sb, List<String> lines) {
        for (var l : lines) {
            if (l.equals(".")) sb.append("\\.\n");
            else if (l.equals("\\")) sb.append("\\\\\n");
            else sb.append(l).append('\n');
        }
        sb.append(".\n");
    }

    /**
     * Returns the canonical string path for a ProjectFile, used in tag formatting.
     * This uses ProjectFile.toString(), which should correspond to the full workspace path.
     */
    private static String canonicalPath(ProjectFile file) {
        return file.toString();
    }
}
