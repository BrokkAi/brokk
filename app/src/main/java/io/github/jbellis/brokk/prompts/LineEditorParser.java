package io.github.jbellis.brokk.prompts;

import io.github.jbellis.brokk.LineEdit;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * ED-mode parser for Brokk line-edit fences.
 *
 * Parses BRK_EDIT_EX/BRK_EDIT_EX_END blocks (with ED commands) and BRK_EDIT_RM lines into concrete
 * LineEdit operations directly. Collects recoverable parse failures, and optionally reports an
 * unrecoverable ParseError when the input ends in the middle of an edit with no safe resynchronization.
 *
 * Legacy typed tags are not supported.
 */
public final class LineEditorParser {
    private static final Logger logger = LogManager.getLogger(LineEditorParser.class);

    public static final LineEditorParser instance = new LineEditorParser();

    private LineEditorParser() {}

    // Public result model
    public enum ParseError {
        EOF_IN_BLOCK,
        EOF_IN_BODY
    }

    public enum ParseFailureReason {
        MISSING_FILENAME,
        MISSING_END_FENCE,
        MISSING_ANCHOR,
        ANCHOR_SYNTAX,
        TOO_MANY_ANCHORS
    }

    public record ParseFailure(ParseFailureReason reason, String message, String snippet) {}

    public record ParseResult(List<LineEdit> edits, List<ParseFailure> failures, @Nullable ParseError error) {
        public boolean hasEdits() {
            return !edits.isEmpty();
        }
    }

    // Patterns and constants
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

    // Unchecked control flow with a reasoned failure
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
        // Classify as MISSING_ANCHOR if the next line doesn't even start with '@'
        // Reserve ANCHOR_SYNTAX for lines that start with '@' but are malformed.
        var trimmed = line.trim();
        if (trimmed.startsWith("@")) {
            throw new Abort(ParseFailureReason.ANCHOR_SYNTAX, malformedPrefix + line);
        } else {
            throw new Abort(ParseFailureReason.MISSING_ANCHOR, malformedPrefix + line);
        }
    }

    // Ensures we don't have an unexpected extra @N| line after expected anchors
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

    private static String unescapeBodyLine(String line) {
        if (line.equals("\\.")) return ".";
        if (line.equals("\\\\")) return "\\";
        return line;
    }

    private record BodyReadResult(
            List<String> body,
            int nextIndex,
            boolean implicitClose,
            boolean explicitTerminator,
            List<String> snippetLines,
            boolean truncated) {}

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

    // Parse implementation
    public ParseResult parse(String content) {
        var edits = new ArrayList<LineEdit>();
        var failures = new ArrayList<ParseFailure>();
        @Nullable ParseError fatalError = null;

        var lines = content.split("\n", -1);
        int i = 0;

        StringBuilder blockSnippet = null; // full edit up to error (bounded body sample)
        final int MAX_BODY_LINES_IN_SNIPPET = 10;

        var pendingBlockEdits = new ArrayList<LineEdit>();

        while (i < lines.length) {
            var raw = lines[i];
            var trimmed = raw.trim();

            // Delete
            if (trimmed.startsWith("BRK_EDIT_RM")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    failures.add(new ParseFailure(ParseFailureReason.MISSING_FILENAME, "BRK_EDIT_RM missing filename.",
                            blockSnippet == null ? "" : blockSnippet.toString()));
                    i++;
                    continue;
                }
                var path = trimmed.substring(sp + 1).trim();
                edits.add(new LineEdit.DeleteFile(path));
                i++;
                continue;
            }

            // Edit block
            if (trimmed.equals("BRK_EDIT_EX") || trimmed.startsWith("BRK_EDIT_EX ")) {
                int sp = trimmed.indexOf(' ');
                if (sp < 0) {
                    failures.add(new ParseFailure(ParseFailureReason.MISSING_FILENAME, "BRK_EDIT_EX missing filename.",
                            blockSnippet == null ? "" : blockSnippet.toString()));
                    i++;
                    continue;
                }
                var path = trimmed.substring(sp + 1).trim();

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

                    // Range: n[,m] c|d
                    if (mRange.matches()) {
                        var addr1 = mRange.group(1);
                        var addr2 = mRange.group(2);
                        char op = Character.toLowerCase(mRange.group(3).charAt(0));

                        int n1 = parseAddr(addr1);
                        int n2 = (addr2 == null) ? n1 : parseAddr(addr2);

                        blockSnippet.append(line).append('\n');
                        i++; // consume command line

                        try {
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
                                endAnchorLine = beginAnchorLine;
                                endAnchorContent = beginAnchorContent;
                            }

                            checkForExtraAnchorsForSameOp(lines, i);

                            if (op == 'd') {
                                int begin = (n1 < 1) ? 1 : n1;
                                int end = n2;
                                var beginAnchor = new LineEdit.Anchor(beginAnchorLine, beginAnchorContent);
                                LineEdit.Anchor endAnchor = endAnchorLine.isBlank() ? null
                                        : new LineEdit.Anchor(endAnchorLine, endAnchorContent);

                                pendingBlockEdits.add(new LineEdit.EditFile(
                                        path, begin, end, "", beginAnchor, endAnchor));

                                while (i < lines.length && lines[i].trim().isEmpty()) i++;
                                if (i >= lines.length) { sawEndFence = true; break; }
                                var la = lines[i].trim();
                                if (la.equals("BRK_EDIT_EX") || la.startsWith("BRK_EDIT_EX ")) {
                                    sawEndFence = true; break;
                                }
                                continue;
                            } else {
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
                            failures.add(new ParseFailure(e.reason, Objects.toString(e.getMessage(), e.toString()),
                                    blockSnippet == null ? "" : blockSnippet.toString()));
                            boolean foundEnd = false;
                            while (i < lines.length && !lines[i].trim().equals(END_FENCE)) i++;
                            if (i < lines.length) {
                                i++; // consume END if present
                                foundEnd = true;
                            }
                            sawEndFence = foundEnd;
                            break;
                        }
                    }

                    // Append: n a
                    if (mIA.matches()) {
                        var addr = mIA.group(1);
                        int n = parseAddr(addr);

                        blockSnippet.append(line).append('\n');
                        i++; // to anchor

                        try {
                            var anchor = readAnchorLine(lines, i,
                                    "Malformed or missing anchor line after append command: ");
                            i = anchor.nextIndex();

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
                            failures.add(new ParseFailure(e.reason, Objects.toString(e.getMessage(), e.toString()),
                                    blockSnippet == null ? "" : blockSnippet.toString()));
                            boolean foundEnd = false;
                            while (i < lines.length && !lines[i].trim().equals(END_FENCE)) i++;
                            if (i < lines.length) {
                                i++; // consume END if present
                                foundEnd = true;
                            }
                            sawEndFence = foundEnd;
                            break;
                        }
                    }

                    // Unknown inside block; skip
                    i++;
                }

                if (!sawEndFence) {
                    if (fatalError == null) fatalError = ParseError.EOF_IN_BLOCK;
                    failures.add(new ParseFailure(ParseFailureReason.MISSING_END_FENCE,
                            "Missing BRK_EDIT_EX_END for " + path, blockSnippet == null ? "" : blockSnippet.toString()));
                }

                edits.addAll(pendingBlockEdits);
                // reset block state
                blockSnippet = null;
                pendingBlockEdits.clear();
                continue;
            }

            // Plain text outside of edits is ignored
            i++;
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

    // Prompt surface (unchanged, but no legacy tags)
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
- Any additional anchor lines (`@N| ...`) beyond the required ones are a parse error.

Common mistakes & self-checks:
- If you wrote two `@N|` lines, your command must be `n,m c` or `n,m d` with those same N values.
- Count addresses on the command line; emit exactly that many anchors (no more, no fewer).
- Anchors are for verification of endpoints only; never anchor all lines in the middle of a range.

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
- ASCII only; no Markdown code fences, diffs, or JSON in edit blocks.
""".stripIndent();
    }

    public List<ChatMessage> exampleMessages() {
        return List.of(
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
              """.stripIndent())
        );
    }

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
}
