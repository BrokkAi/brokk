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
 *   - "n i"           insert before line n; body required
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
            permits EdCommand.InsertBefore, EdCommand.AppendAfter, EdCommand.ChangeRange, EdCommand.DeleteRange {

        /** n i  — insert before line n; body required. */
        record InsertBefore(int line, List<String> body) implements EdCommand {}

        /** n a  — append after line n; body required (0a allowed). */
        record AppendAfter(int line, List<String> body) implements EdCommand {}

        /** n1,n2 c  — replace inclusive range; body required. If n2 omitted, n1==n2. */
        record ChangeRange(int begin, int end, List<String> body) implements EdCommand {}

        /** n1,n2 d  — delete inclusive range. If n2 omitted, n1==n2. */
        record DeleteRange(int begin, int end) implements EdCommand {}
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
     *     n i     (insert before n)      body until a single '.' line; last body in a block may omit '.'
     *     n a     (append after n)       body as above; '0 a' is allowed
     *     n[,m] c (change range)         body as above
     *     n[,m] d (delete range)         no body
     * - All addresses are absolute numeric (1-based; 0 allowed only for 'a'); no regex, no navigation.
     * - 'w', 'q' and other non-edit commands are ignored. Shell ('!') is ignored but noted as a parse warning.
     */
    public ExtendedParseResult parse(String content) {
        // Minimal guard for legacy typed-tag inputs (e.g., <brk_edit_file ...> ... </brk_edit_file>)
        // Our parser is ED-fence based. If we detect an open <brk_edit_file> without the correct
        // </brk_edit_file> close (including the alias-mismatch case like </brk_update_file>),
        // we treat the whole input as plain text and surface a clear parse error.
        var lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("<brk_edit_file")) {
            if (!lower.contains("</brk_edit_file>")) {
                var onlyText = new ArrayList<OutputPart>();
                onlyText.add(new OutputPart.Text(content));
                return new ExtendedParseResult(onlyText, "Missing closing </brk_edit_file> tag");
            }
            // If both open and close typed tags are present, we still do not support typed parsing here.
            // Treat it as plain text with a helpful error so callers know why nothing was parsed.
            var onlyText = new ArrayList<OutputPart>();
            onlyText.add(new OutputPart.Text(content));
            return new ExtendedParseResult(onlyText,
                                           "Typed <brk_edit_file> blocks are not supported by ED parser; use BRK_EDIT_EX / BRK_EDIT_EX_END.");
        }

        var parts = new ArrayList<OutputPart>();
        var errors = new ArrayList<String>();

        // Split preserving trailing empty segment if text ends with '\n'
        var lines = content.split("\n", -1);
        int i = 0;

        var textBuf = new StringBuilder();

        while (i < lines.length) {
            var line = lines[i];
            var trimmed = line.trim();

            // helpers to flush accumulated text
            Runnable flushText = () -> {
                if (textBuf.length() > 0) {
                    parts.add(new OutputPart.Text(textBuf.toString()));
                    textBuf.setLength(0);
                }
            };

            // BRK_EDIT_RM <path>
            if (line.startsWith("BRK_EDIT_RM")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    // malformed; keep as text to avoid accidental edits, and record an error
                    textBuf.append(line);
                    if (i < lines.length - 1) textBuf.append('\n');
                    errors.add("BRK_EDIT_RM missing filename.");
                    i++;
                    continue;
                }
                String path = trimmed.substring(sp + 1).trim();
                flushText.run();
                parts.add(new OutputPart.Delete(path));
                i++; // consume this line
                continue;
            }

            // BRK_EDIT_EX <path>
            if (line.startsWith("BRK_EDIT_EX")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    // malformed; keep as text
                    textBuf.append(line);
                    if (i < lines.length - 1) textBuf.append('\n');
                    errors.add("BRK_EDIT_EX missing filename.");
                    i++;
                    continue;
                }
                String path = trimmed.substring(sp + 1).trim();

                flushText.run();

                // Parse until BRK_EDIT_EX_END (required)
                int blockStartIndex = i; // index of ED header line
                i++; // advance to first command/body line
                var commands = new ArrayList<EdCommand>();
                boolean sawEndFence = false;

                while (i < lines.length) {
                    var cmdLine = lines[i];
                    var cmdTrim = cmdLine.trim();

                    // end of block
                    if (cmdLine.equals("BRK_EDIT_EX_END")) {
                        sawEndFence = true;
                        i++; // consume END
                        break;
                    }

                    // ignore blank lines and non-edit noise ('w', 'q', etc.)
                    if (cmdTrim.isEmpty()
                            || cmdTrim.equals("w") || cmdTrim.equals("q")
                            || cmdTrim.equals("wq") || cmdTrim.equals("qw")) {
                        i++;
                        continue;
                    }

                    // shell not supported; ignore but note
                    if (cmdTrim.startsWith("!")) {
                        errors.add("Shell command ignored inside BRK_EDIT_EX: " + cmdTrim);
                        i++;
                        continue;
                    }

                    // Match range command:  n[,m] [c|d]
                    var range = RANGE_CMD.matcher(cmdTrim);
                    // Match insert/append:  n [i|a]
                    var ia = IA_CMD.matcher(cmdTrim);

                    if (range.matches()) {
                        int n1 = Integer.parseInt(range.group(1));
                        int n2 = (range.group(2) == null) ? n1 : Integer.parseInt(range.group(2));
                        char op = Character.toLowerCase(range.group(3).charAt(0));
                        if (op == 'd') {
                            commands.add(new EdCommand.DeleteRange(n1, n2));
                            i++; // move to next command
                        } else { // 'c'
                            // read body until '.' or BRK_EDIT_EX_END ('.' optional for final body)
                            i++;
                            var body = new ArrayList<String>();
                            boolean implicitClose = false;
                            while (i < lines.length) {
                                var bodyLine = lines[i];
                                var bodyTrim = bodyLine.trim();
                                if (bodyTrim.equals("BRK_EDIT_EX_END")) {
                                    // treat as implicit '.' for the final command
                                    implicitClose = true;
                                    sawEndFence = true;
                                    break;
                                }
                                if (bodyTrim.equals(".")) {
                                    i++; // consume terminator
                                    break;
                                }
                                body.add(unescapeBodyLine(bodyLine));
                                i++;
                            }
                            commands.add(new EdCommand.ChangeRange(n1, n2, body));
                            if (implicitClose) {
                                // outer while will see END on next iteration
                            }
                        }
                        continue;
                    }

                    if (ia.matches()) {
                        ia.reset(); ia.matches(); // safe; we already matched
                        var addr = ia.group(1);
                        // Sentinel for '$' (end-of-file). We'll resolve during materialization/apply.
                        int n = "$".equals(addr) ? Integer.MAX_VALUE : Integer.parseInt(addr);
                        char op = Character.toLowerCase(ia.group(2).charAt(0));

                        i++; // advance to body
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
                                i++; // consume terminator
                                break;
                            }
                            body.add(unescapeBodyLine(bodyLine));
                            i++;
                        }
                        if (op == 'i') {
                            commands.add(new EdCommand.InsertBefore(n, body));
                        } else {
                            commands.add(new EdCommand.AppendAfter(n, body));
                        }
                        if (implicitClose) {
                            // outer while will see END on next iteration
                        }
                        continue;
                    }

                    // Unrecognized line inside ED block: ignore silently to minimize parse churn
                    i++;
                }

                if (!sawEndFence) {
                    // Missing END: treat the whole block as text and report a parse error
                    var sb = new StringBuilder();
                    sb.append("BRK_EDIT_EX ").append(path).append('\n');
                    for (int k = blockStartIndex + 1; k < lines.length; k++) {
                        sb.append(lines[k]);
                        if (k < lines.length - 1) sb.append('\n');
                    }
                    parts.add(new OutputPart.Text(sb.toString()));
                    errors.add("Missing BRK_EDIT_EX_END for " + path);
                    break; // nothing more to parse
                }

                // Emit ED block
                parts.add(new OutputPart.EdBlock(path, List.copyOf(commands)));
                continue;
            }

            // Not a fence; accumulate as text (preserving newlines)
            textBuf.append(line);
            if (i < lines.length - 1) textBuf.append('\n');
            i++;
        }

        // >>> NEW: flush remaining accumulated text
        if (textBuf.length() > 0) {
            parts.add(new OutputPart.Text(textBuf.toString()));
        }

        var error = errors.isEmpty() ? null : String.join("\n", errors);
        if (error != null && logger.isDebugEnabled()) {
            logger.debug("LineEditorParser (ED mode) parse warnings:\n{}", error);
        }
        return new ExtendedParseResult(parts, error);
    }

    // Patterns for ED commands (numeric only; no regex, no navigation)
    // Patterns for ED commands (numeric only; no regex, no navigation)
    private static final Pattern RANGE_CMD = Pattern.compile("(?i)^(\\d+)\\s*(?:,\\s*(\\d+))?\\s*([cd])$");
    private static final Pattern IA_CMD    = Pattern.compile("(?i)^(\\d+|\\$)\\s*([ia])$");

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
                    if (cmd instanceof EdCommand.InsertBefore ib) {
                        // Clamp 0 -> 1 to satisfy EditFile constructor precondition (>= 1).
                        int addr = ib.line();
                        int begin = (addr <= 1) ? 1 : addr; // preserve Integer.MAX_VALUE sentinel
                        String body = String.join("\n", ib.body());
                        editFiles.add(new LineEdit.EditFile(pf, begin, begin - 1, body));
                    } else if (cmd instanceof EdCommand.AppendAfter aa) {
                        // NOTE: if aa.line() == Integer.MAX_VALUE (parsed from '$'), avoid overflow on +1
                        int begin = (aa.line() == Integer.MAX_VALUE) ? Integer.MAX_VALUE : aa.line() + 1; // 0a => begin=1; $a => end
                        String body = String.join("\n", aa.body());
                        editFiles.add(new LineEdit.EditFile(pf, begin, begin - 1, body));
                    } else if (cmd instanceof EdCommand.ChangeRange cr) {
                        String body = String.join("\n", cr.body());
                        editFiles.add(new LineEdit.EditFile(pf, cr.begin(), cr.end(), body));
                    } else if (cmd instanceof EdCommand.DeleteRange dr) {
                        editFiles.add(new LineEdit.EditFile(pf, dr.begin(), dr.end(), ""));
                    }
                }
            }
        }

        // Sort by file then descending begin/end (last-edits-first) for cross-file determinism
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
The BRK_EDIT_EX format is a line-oriented text-editing format that supports only a small subset of classic `ex`:
**i**, **a**, **c**, **d** with absolute addresses. Addresses are 1-based integers. For **i** and **a** only,
you may also use the special pseudo-addresses:
- `0`  -> the very start of the file (before the first line)
- `$`  -> the end of the file (after the last line)

You will be shown file contents with each line prefixed by `N:` in the workspace listing.
These numbers are NOT part of the file text; use them only to reference line numbers.
Do not re-count; rely on the provided numbering.

Emit edits using ONLY the following fences (ALL CAPS, each on its own line, no Markdown code fences):

BRK_EDIT_EX <full/path>
n i            # insert before line n     (body required; `0 i` inserts at start; `$ i` inserts at end)
...body...
.
n a            # append after line n      (body required; `0 a` inserts at start; `$ a` appends at end)
...body...
.
n[,m] c        # replace inclusive range  (body required; ranges are numeric only; `$` not allowed here)
...body...
.
n[,m] d        # delete inclusive range   (no body)
BRK_EDIT_EX_END

Path rules:
- `<full/path>` is the remainder of the fence line after the first space, trimmed; it may include spaces.

Body rules:
- For **i/a/c**, the body ends with a single dot `.` on a line by itself.
  For the *final* body in a block, that terminating dot may be omitted.
- To include a literal `.` line, write `\\.`.
  To include a literal `\\` line, write `\\\\`.

Noise handling:
- `w`, `q`, `wq`, `qw` are ignored.
- Shell commands starting with `!` are not supported; they will be ignored and reported as a warning.

Conventions and constraints:
- Ranges `n,m` are inclusive; both `n` and `m` must be integers with `1 <= n <= m`.
- `$` and `0` are allowed **only** with `i` and `a`, not with `c` or `d`.
- Keep edits minimal and non-overlapping. Emit commands **last-edits-first** within each file to avoid
  line-number shifts. The system will still sort, but correct ordering reduces mistakes.
- Do not modify lines you do not explicitly touch. Do not include file listings or unrelated text.

Creating or removing files:
- To create a new file, use `BRK_EDIT_EX <path>` with  `0 a` or `1 i` containing the entire file body.
  (Do NOT use a replace range on a non-existent file.)
- To remove a file, use: `BRK_EDIT_RM <path>` on a single line (no END fence).

## Examples (do not wrap these in Markdown fences)

# Append one line at end-of-file
BRK_EDIT_EX src/main.py
$ a
print("done")
.
BRK_EDIT_EX_END

# Insert two lines at the start of the file
BRK_EDIT_EX README.md
0 i
# Project Title
A short description.
.
BRK_EDIT_EX_END

# Replace lines 10..12 with a single line
BRK_EDIT_EX src/Main.java
10,12 c
System.out.println("Replaced!");
.
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
                                      return str(math.factorial(n))
                                      .
                                      9,14 d
                                      1 i
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
                                        12:
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
                                      # Usage: hello()
                                      .
                                      9,12 d
                                      1 i
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
- Use `1 i` / `0 a` to insert at the start of a file, and `$ i` to insert at the end.
- Do NOT use `$` with `c` or `d`. Ranges for `c` and `d` must be numeric (1-based) and inclusive.
- To create a new file, use `0 a` or `1 i` with the whole file body; do not attempt a replace on a missing file.
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
                if (c instanceof EdCommand.InsertBefore ib) {
                    sb.append(ib.line()).append(" i\n");
                    renderBody(sb, ib.body());
                } else if (c instanceof EdCommand.AppendAfter aa) {
                    sb.append(aa.line()).append(" a\n");
                    renderBody(sb, aa.body());
                } else if (c instanceof EdCommand.ChangeRange cr) {
                    sb.append(cr.begin()).append(',').append(cr.end()).append(" c\n");
                    renderBody(sb, cr.body());
                } else if (c instanceof EdCommand.DeleteRange dr) {
                    sb.append(dr.begin()).append(',').append(dr.end()).append(" d\n");
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
                sb.append(beginLine).append(" i\n");
                renderBody(sb, content.isEmpty() ? List.of() : content.lines().toList());
            } else if (content.isEmpty()) {
                sb.append(beginLine).append(',').append(endLine).append(" d\n");
            } else {
                sb.append(beginLine).append(',').append(endLine).append(" c\n");
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
