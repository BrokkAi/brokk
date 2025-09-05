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
import java.util.Locale;
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

    // NEW: helper to detect/parse @N| ... anchors; returns the addr or null
    private static @Nullable String matchAnchorAddr(String line) {
        var m = ANCHOR_LINE.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    private static int consumeExtraAnchorsForRange(
            String[] lines, int startIndex, int n, @Nullable Integer mOrNull,
            String opName, String path, List<String> errors) {

        int i = startIndex;
        while (i < lines.length) {
            String addrStr = matchAnchorAddr(lines[i]);
            if (addrStr == null) break;

            int addr = parseAddr(addrStr);

            boolean isError;
            if (mOrNull == null) {
                // single-address op: any additional @n| ... is an error
                isError = (addr == n);
            } else {
                int lo = Math.min(n, mOrNull);
                int hi = Math.max(n, mOrNull);
                // duplicates of endpoints OR any interior anchor are errors
                isError = (addr >= lo && addr <= hi);
            }

            if (!isError) break; // likely start of body or next command; do not consume

            errors.add("Too many anchors after " + opName + " command for " + path + ": " + lines[i]);
            i++; // consume the offending anchor line
        }
        return i;
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

        Runnable flushText = () -> {
            if (!textBuf.isEmpty()) {
                parts.add(new OutputPart.Text(textBuf.toString()));
                textBuf.setLength(0);
            }
        };

        while (i < lines.length) {
            var line = lines[i];
            var trimmed = line.trim();

            // BRK_EDIT_RM <path>
            if (trimmed.startsWith("BRK_EDIT_RM")) {
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
            if (trimmed.equals("BRK_EDIT_EX") || trimmed.startsWith("BRK_EDIT_EX ")) {
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

                    if (cmdTrim.equals(END_FENCE)) {
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
                        var addr1 = range.group(1);
                        var addr2 = range.group(2);
                        int n1 = parseAddr(addr1);
                        int n2 = (addr2 == null) ? n1 : parseAddr(addr2);
                        char op = Character.toLowerCase(range.group(3).charAt(0));

                        i++; // move past the command line

                        var opName = (op == 'd') ? "delete" : "change";
                        var anchors = readRangeAnchors(lines, i, opName, addr1, addr2, path, errors);
                        i = anchors.nextIndex();

                        // NEW: flag & consume any extra @N| ... anchors before body / next command
                        i = consumeExtraAnchorsForRange(
                                lines, i, n1, (addr2 == null ? null : Integer.valueOf(n2)), opName, path, errors);

                        if (op == 'd') {
                                commands.add(new EdCommand.DeleteRange(
                                        n1, n2, anchors.beginAddr(), anchors.beginContent(), anchors.endAddr(), anchors.endContent()));
                                continue;
                            } else { // 'c'
                                var bodyRes = readBody(lines, i);
                                i = bodyRes.nextIndex();
                                if (bodyRes.implicitClose()) {
                                    sawEndFence = true;
                                }
                                commands.add(new EdCommand.ChangeRange(
                                        n1, n2, bodyRes.body(), anchors.beginAddr(), anchors.beginContent(), anchors.endAddr(), anchors.endContent()));
                                continue;
                            }
                    }

                    if (ia.matches()) {
                        var addr = ia.group(1);
                        int n = parseAddr(addr);

                        i++; // move to (optional) anchor line
                        var allowOmit = isZeroOrDollar(addr);
                        var anchor = readAnchorLine(
                                lines, i, addr, allowOmit,
                                "Append anchor",
                                "Malformed or missing anchor line after append command: ",
                                "Missing anchor line after append command for " + path,
                                errors);
                        i = anchor.nextIndex();

                        // NEW: flag & consume duplicate @n| ... anchors before the body
                        i = consumeExtraAnchorsForRange(
                                lines, i, n, null, "append", path, errors);

                        var bodyRes = readBody(lines, i);
                        i = bodyRes.nextIndex();
                        if (bodyRes.implicitClose()) {
                            sawEndFence = true;
                        }
                        commands.add(new EdCommand.AppendAfter(n, bodyRes.body(), anchor.addr(), anchor.content()));
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

        if (!textBuf.isEmpty()) {
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
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\s*@([0-9]+|\\$)\\s*\\|\\s*(.*)$");
    private static final String END_FENCE = "BRK_EDIT_EX_END";

    // Lightweight result records (Java 21)
    private record BodyReadResult(List<String> body, int nextIndex, boolean implicitClose) {}
    private record AnchorReadResult(String addr, String content, int nextIndex) {}
    private record RangeAnchors(String beginAddr, String endAddr, String beginContent, String endContent, int nextIndex) { }

    private static boolean isZeroOrDollar(String addr) {
        return "0".equals(addr) || "$".equals(addr);
    }

    /** Reads the body for 'a' and 'c'. Stops at a single '.' line or at BRK_EDIT_EX_END (implicit close, without consuming it). */
    private static BodyReadResult readBody(String[] lines, int startIndex) {
        var body = new ArrayList<String>();
        int i = startIndex;
        boolean implicitClose = false;

        while (i < lines.length) {
            var bodyLine = lines[i];
            var bodyTrim = bodyLine.trim();

            if (END_FENCE.equals(bodyTrim)) {
                implicitClose = true;    // do not consume END fence; outer loop will handle
                break;
            }
            if (".".equals(bodyTrim)) {
                i++;                     // consume explicit terminator
                break;
            }
            body.add(unescapeBodyLine(bodyLine));
            i++;
        }
        return new BodyReadResult(body, i, implicitClose);
    }

    /**
     * Reads a single anchor line for an expected address. If allowOmit==true and the next line is not an anchor,
     * nothing is consumed and no error is reported. On malformed lines (when not allowed to omit), we report an error
     * and consume one line to avoid infinite loops, mirroring the previous behavior.
     */
    private static AnchorReadResult readAnchorLine(
            String[] lines, int index, String expectedAddr, boolean allowOmit,
            String mismatchLabel, String malformedPrefix, String eofMessage, List<String> errors) {

        int i = index;

        if (i >= lines.length) {
            if (!allowOmit) {
                errors.add(eofMessage);
            }
            return new AnchorReadResult(expectedAddr, "", i);
        }

        var line = lines[i];
        var m = ANCHOR_LINE.matcher(line);
        if (m.matches()) {
            var parsedAddr = m.group(1);
            var parsedContent = m.group(2);

            // Special case: if expected is "$" but a numeric anchor was provided, allow it (no parse error).
            if (!parsedAddr.equals(expectedAddr)) {
                if ("$".equals(expectedAddr) && !"0".equals(parsedAddr) && !"$".equals(parsedAddr)) {
                    // accept numeric anchor for "$" without error
                    return new AnchorReadResult(parsedAddr, parsedContent, i + 1);
                } else {
                    errors.add(mismatchLabel + " line '" + parsedAddr + "' does not match address '" + expectedAddr + "'");
                }
            }
            return new AnchorReadResult(parsedAddr, parsedContent, i + 1); // consume anchor line
        }

        if (!allowOmit) {
            errors.add(malformedPrefix + line);
            return new AnchorReadResult(expectedAddr, "", i + 1); // consume questionable line to avoid infinite loop
        }

        // Optional and not present; do not consume; keep expected address
        return new AnchorReadResult(expectedAddr, "", i);
    }

    /**
     * Reads anchors for range commands ('change' / 'delete') and returns normalized end address + contents.
     * Behavior matches the previous inlined logic, including error wording and consumption rules.
     */
    private static RangeAnchors readRangeAnchors(
            String[] lines, int startIndex, String opName, String addr1, @Nullable String addr2, String path, List<String> errors) {

        int i = startIndex;

        // Begin anchor: required unless 0/$
        var a1 = readAnchorLine(
                lines, i, addr1, isZeroOrDollar(addr1),
                capitalize(opName) + " begin anchor",
                "Malformed or missing first anchor after " + opName + " command: ",
                "Missing anchor line(s) after " + opName + " command for " + path,
                errors);
        i = a1.nextIndex();

        // End anchor logic differs per op and presence of addr2
        if (addr2 == null) {
            if ("change".equals(opName)) {
                // Single-line change: end anchor equals begin
                return new RangeAnchors(a1.addr(), a1.addr(), a1.content(), a1.content(), i);
            } else { // delete
                // Single-address delete: no end anchor
                return new RangeAnchors(a1.addr(), "", a1.content(), "", i);
            }
        }

        // Range form: parse second anchor, required unless 0/$
        var a2 = readAnchorLine(
                lines, i, addr2, isZeroOrDollar(addr2),
                capitalize(opName) + " end anchor",
                "Malformed or missing second anchor after " + opName + " command: ",
                "Missing second anchor line for range " + opName + " command for " + path,
                errors);
        i = a2.nextIndex();

        return new RangeAnchors(a1.addr(), a2.addr(), a1.content(), a2.content(), i);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        char c0 = s.charAt(0);
        if (Character.isUpperCase(c0)) return s;
        return Character.toUpperCase(c0) + s.substring(1);
    }

    private static int parseAddr(String address) {
        return "$".equals(address) ? Integer.MAX_VALUE : Integer.parseInt(address);
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
                        var beginAnchor = new LineEdit.Anchor(aa.anchorLine(), aa.anchorContent());
                        editFiles.add(new LineEdit.EditFile(pf, begin, begin - 1, body, beginAnchor, null));
                    } else if (cmd instanceof EdCommand.ChangeRange cr) {
                        int begin = (cr.begin() < 1) ? 1 : cr.begin();
                        int end = cr.end();
                        String body = String.join("\n", cr.body());
                        var beginAnchor = new LineEdit.Anchor(cr.beginAnchorLine(), cr.beginAnchorContent());
                        @Nullable LineEdit.Anchor endAnchor = cr.endAnchorLine().isBlank()
                                ? null
                                : new LineEdit.Anchor(requireNonNull(cr.endAnchorLine()), requireNonNull(cr.endAnchorContent()));
                        editFiles.add(new LineEdit.EditFile(pf, begin, end, body, beginAnchor, endAnchor));
                    } else if (cmd instanceof EdCommand.DeleteRange dr) {
                        int begin = (dr.begin() < 1) ? 1 : dr.begin();
                        int end = dr.end();
                        var beginAnchor = new LineEdit.Anchor(dr.beginAnchorLine(), dr.beginAnchorContent());
                        @Nullable LineEdit.Anchor endAnchor = dr.endAnchorLine().isBlank()
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
append   ::= n SP "a" NL [ anchor(n) ] body
change   ::= n ["," m] SP "c" NL [ anchor(n) ] [ anchor(m) ] body
delete   ::= n ["," m] SP "d" NL [ anchor(n) ] [ anchor(m) ]
anchor(x)::= "@" x "|" SP? text NL    (* required unless x ∈ {"0","$"} *)
body     ::= { body_line } ( "." NL | /* implicit before END */ )
body_line::= "\\." NL | "\\\\" NL | other NL
n,m      ::= addr ; addr ::= "0" | "$" | INT
INT     ::= "1".."9" { DIGIT } ; DIGIT ::= "0".."9"
SP      ::= (" " | "\\t")+ ; NL ::= "\\n"

To elaborate, supported edits are
a: append after given address
c: change given address or range
d: delete given address or range

An address may be a single line number, or a range denoted with commas. Additionally, two special addresses are supported:
- `0`  -> the very start of the file (before the first line)
- `$`  -> the end of the file (after the last line)

Anchors are the current content at an address line, used to validate that the edit is applied to the expected version.
Provide one anchor per address parameter (except you may omit anchors for `0` and `$`). Anchors use the form:
    @N| <exact current content of line N>
DO NOT provide anchors for the entire range; only for the start and end lines. If there is only one address,
provide only one anchor.

NEVER include more than two anchors per edit.

Body rules:
- Only **a** and **c** have bodies. The body ends with a single dot `.` on its own line.
- To include a literal `.` or `\\` line in the body, use `\\.` and `\\\\` respectively (anchors are unescaped).

Emit edits using ONLY these fences (ALL CAPS, each on its own line; no Markdown fences):

Path rules:
- <full_path> is the remainder of the fence line after the first space, trimmed; it may include spaces.

Conventions and constraints:
- All addresses are absolute and inclusive for ranges; **n,m** are 1-based integers (with `0` and `$` as described).
- Do not overlap edits. Emit commands **last-edits-first** within each file to avoid line shifts.
- To create a new file, use `BRK_EDIT_EX <path>` with `0 a` and the entire file body; you may omit the `0` anchor.
- To remove a file, use `BRK_EDIT_RM <path>` on a single line (no END fence).
- `n,m c` ranges are INCLUSIVE, MAKE SURE YOU ARE NOT OFF BY ONE.
- ASCII only; no Markdown code fences, diffs, or JSON around the edit blocks.
- Non-command, non-anchor, non-body lines inside a BRK_EDIT_EX block will be ignored by the parser; do not include commentary inside the block.
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
                10:     \\\"\\\"\\\"print a greeting\\\"\\\"\\\"
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
                  \\\"\\\"\\\"print a greeting\\\"\\\"\\\"
                  print("hello")
              .
              BRK_EDIT_EX_END
              
              BRK_EDIT_EX main.py
              $ a
              # Usage: hello()
              .
              9,11 d
              @9| def hello():
              @11|     print("hello")
              0 a
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
- Keep edits small and emit last-edits-first within each file. Avoid overlapping ranges entirely;
  combine into a single edit instead.
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
                    sb.append("@").append(aa.anchorLine()).append("| ").append(aa.anchorContent()).append('\n');
                    renderBody(sb, aa.body());
                } else if (c instanceof EdCommand.ChangeRange cr) {
                    sb.append(addrToString(cr.begin())).append(',').append(addrToString(cr.end())).append(" c\n");
                    sb.append("@").append(cr.beginAnchorLine()).append("| ").append(cr.beginAnchorContent()).append('\n');
                    if (!cr.endAnchorLine().isBlank()) {
                        sb.append("@").append(cr.endAnchorLine()).append("| ").append(requireNonNull(cr.endAnchorContent())).append('\n');
                    }
                    renderBody(sb, cr.body());
                } else if (c instanceof EdCommand.DeleteRange dr) {
                    sb.append(addrToString(dr.begin())).append(',').append(addrToString(dr.end())).append(" d\n");
                    sb.append("@").append(dr.beginAnchorLine()).append("| ").append(dr.beginAnchorContent()).append('\n');
                    if (!dr.endAnchorLine().isBlank()) {
                        sb.append("@").append(dr.endAnchorLine()).append("| ").append(requireNonNull(dr.endAnchorContent())).append('\n');
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
                    sb.append("@").append(ef.beginAnchor().address()).append("| ").append(ef.beginAnchor().content()).append('\n');
                }
                renderBody(sb, content.isEmpty() ? List.of() : content.lines().toList());
            } else if (content.isEmpty()) {
                // delete
                sb.append(addrToString(beginLine)).append(',').append(addrToString(endLine)).append(" d\n");
                if (ef.beginAnchor() != null) {
                    sb.append("@").append(ef.beginAnchor().address()).append("| ").append(ef.beginAnchor().content()).append('\n');
                }
            } else {
                // change
                sb.append(addrToString(beginLine)).append(',').append(addrToString(endLine)).append(" c\n");
                if (ef.beginAnchor() != null) {
                    sb.append("@").append(ef.beginAnchor().address()).append("| ").append(ef.beginAnchor().content()).append('\n');
                }
                if (ef.endAnchor() != null) {
                    sb.append("@").append(ef.endAnchor().address()).append("| ").append(ef.endAnchor().content()).append('\n');
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
