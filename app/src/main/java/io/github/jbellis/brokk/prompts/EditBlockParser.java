package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.EditBlock.FileOperation;
import io.github.jbellis.brokk.EditBlock.UpdateFileChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Parser / renderer for the "apply_patch" format.
 *
 * parse(String) -> List<FileOperation>
 * renderPatch(List<FileOperation>) -> envelope string
 * repr(FileOperation) -> single-op pretty print (no envelope)
 * redact(String) -> elide any apply_patch envelope(s) from free text
 */
public class EditBlockParser {
    public static EditBlockParser instance = new EditBlockParser();
    protected EditBlockParser() {}

    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
                *** Begin Patch
                *** Update File: mathweb/flask/app.py
                @@ imports
                 from flask import Flask
                +import math
                @@ call site
                 def get_factorial(n):
                -    return str(factorial(n))
                +    return str(math.factorial(n))
                *** End Patch
                """.stripIndent()),
                new UserMessage("Refactor hello() into its own file"),
                new AiMessage("""
                *** Begin Patch
                *** Add File: hello.py
                +def hello():
                +    "print a greeting"
                +
                +    print("hello")
                *** Update File: main.py
                @@
                -def hello():
                -    "print a greeting"
                -
                -    print("hello")
                +from hello import hello
                *** End Patch
                """.stripIndent())
        );
    }

    protected final String instructions(String input, @Nullable Object ignored, String reminder) {
        return """
        <rules>
        %s

        Return a single apply_patch envelope:

        *** Begin Patch
        [one or more file operations]
        *** End Patch

        - *** Add File: path
          +line1
          +line2

        - *** Delete File: path

        - *** Update File: path
          [*** Move to: newPath]
          one or more chunks, each:
          @@ [optional anchor]
           [unchanged pre-context lines, ' ' prefix]
          -[old removed lines]
          +[new added lines]
           [unchanged post-context lines, ' ' prefix]
          [*** End of File]

        Keep chunks small, unique, and stable. Ensure a trailing newline.
        %s
        </rules>

        <goal>
        %s
        </goal>
        """.formatted(diffFormatInstructions(), reminder, input);
    }

    public String diffFormatInstructions() {
        return """
        # apply_patch (concise)
        Envelope: *** Begin Patch ... *** End Patch
        Ops: Add/Delete/Update. Update has optional Move-to, and 1+ chunks.
        Chunk layout:
          @@ [anchor]
           pre-context (space-prefixed)
          -old (minus-prefixed)
          +new (plus-prefixed)
           post-context (space-prefixed)
          [*** End of File]
        """.stripIndent();
    }

    /** Remove any full envelopes from conversational text. */
    public String redact(String original) {
        final String placeholder = "[elided apply_patch envelope]";
        if (original.isBlank()) return original;
        var lines = original.split("\n", -1);
        var out = new StringBuilder();
        int i = 0;
        boolean elided = false;
        while (i < lines.length) {
            if (lines[i].trim().equals("*** Begin Patch")) {
                i++;
                while (i < lines.length && !lines[i].trim().equals("*** End Patch")) i++;
                if (i < lines.length) i++; // consume End
                if (out.length() > 0 && out.charAt(out.length()-1) != '\n') out.append('\n');
                out.append(placeholder);
                elided = true;
                continue;
            }
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
            i++;
        }
        return elided ? out.toString() : original;
    }

    // ----------------------------- Parsing -----------------------------

    public static final class PatchParseException extends RuntimeException {
        public PatchParseException(String message) { super(message); }
    }

    /** Parse one or more envelopes found in {@code content}. */
    public List<FileOperation> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        var all = new ArrayList<FileOperation>();
        var lines = content.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            while (i < lines.length && !lines[i].trim().equals("*** Begin Patch")) i++;
            if (i >= lines.length) break;
            i++; // past Begin

            while (i < lines.length && !lines[i].trim().equals("*** End Patch")) {
                if (lines[i].isBlank()) { i++; continue; }
                var t = lines[i].trim();

                if (t.startsWith("*** Add File:")) {
                    var path = headerPath(t, "*** Add File:");
                    i++;
                    var plus = new ArrayList<String>();
                    while (i < lines.length && lines[i].startsWith("+")) {
                        plus.add(lines[i].substring(1));
                        i++;
                    }
                    var body = String.join("\n", plus);
                    if (!body.endsWith("\n") && !plus.isEmpty()) body += "\n";
                    all.add(new EditBlock.AddFile(path, body));
                    continue;
                }

                if (t.startsWith("*** Delete File:")) {
                    var path = headerPath(t, "*** Delete File:");
                    i++;
                    all.add(new EditBlock.DeleteFile(path));
                    continue;
                }

                if (t.startsWith("*** Update File:")) {
                    var path = headerPath(t, "*** Update File:");
                    i++;
                    String moveTo = null;
                    if (i < lines.length && lines[i].trim().startsWith("*** Move to:")) {
                        moveTo = headerPath(lines[i].trim(), "*** Move to:");
                        i++;
                    }

                    var chunks = new ArrayList<UpdateFileChunk>();
                    boolean firstChunk = true;

                    while (i < lines.length) {
                        var tt = lines[i].trim();
                        if (tt.equals("*** End Patch")) break;
                        if (tt.startsWith("*** ") && !tt.equals("*** End of File")) break; // next op
                        if (tt.isBlank()) { i++; continue; }

                        // optional '@@' header
                        String anchor = null;
                        if (tt.startsWith("@@")) {
                            anchor = tt.equals("@@") ? null : tt.substring(2).stripLeading();
                            i++;
                        } else if (!firstChunk) {
                            throw new PatchParseException("Expected '@@' before subsequent chunk in update for " + path);
                        }
                        firstChunk = false;

                        var pre = new ArrayList<String>();
                        var old = new ArrayList<String>();
                        var neu = new ArrayList<String>();
                        var post = new ArrayList<String>();
                        boolean eof = false;

                        enum Mode { PRE, OLD, NEW, POST }
                        Mode mode = Mode.PRE;
                        boolean sawChange = false;

                        while (i < lines.length) {
                            var raw = lines[i];
                            var trim = raw.trim();

                            if (trim.equals("*** End of File")) { eof = true; i++; break; }
                            if (trim.equals("*** End Patch")) break;
                            if (trim.startsWith("*** ") && !trim.equals("*** End of File")) break; // next op
                            if (trim.startsWith("@@")) break; // next chunk

                            if (raw.isEmpty()) {
                                // true blank: treat as context
                                if (mode == Mode.PRE) pre.add("");
                                else if (mode == Mode.POST) post.add("");
                                else {
                                    // During OLD/NEW, a blank line belongs to that section (no prefix), but the format
                                    // mandates a prefix for hunk lines. Enforce that to keep structure simple.
                                    throw new PatchParseException("Blank lines in changes must be prefixed with '-' or '+'");
                                }
                                i++;
                                continue;
                            }

                            char pfx = raw.charAt(0);
                            String payload = raw.length() > 1 ? raw.substring(1) : "";

                            switch (pfx) {
                                case ' ' -> {
                                    if (!sawChange) pre.add(payload);
                                    else { mode = Mode.POST; post.add(payload); }
                                    i++;
                                }
                                case '-' -> {
                                    if (mode == Mode.POST) {
                                        throw new PatchParseException("Only one contiguous -then+ change per chunk is supported");
                                    }
                                    mode = Mode.OLD;
                                    old.add(payload);
                                    sawChange = true;
                                    i++;
                                }
                                case '+' -> {
                                    if (mode == Mode.POST) {
                                        throw new PatchParseException("Only one contiguous -then+ change per chunk is supported");
                                    }
                                    mode = Mode.NEW;
                                    neu.add(payload);
                                    sawChange = true;
                                    i++;
                                }
                                default -> throw new PatchParseException("Unexpected line in hunk: " + raw);
                            }
                        }

                        if (!sawChange) {
                            throw new PatchParseException("Update hunk has no '-' or '+' lines for " + path);
                        }
                        chunks.add(new UpdateFileChunk(anchor,
                                                       List.copyOf(pre),
                                                       List.copyOf(old),
                                                       List.copyOf(neu),
                                                       List.copyOf(post),
                                                       eof));
                    }

                    if (chunks.isEmpty()) {
                        throw new PatchParseException("Update file has no chunks for " + path);
                    }
                    all.add(new EditBlock.UpdateFile(path, moveTo, List.copyOf(chunks)));
                    continue;
                }

                throw new PatchParseException("'" + t + "' is not a valid file operation header.");
            }

            if (i < lines.length && lines[i].trim().equals("*** End Patch")) i++;
        }

        return Collections.unmodifiableList(all);
    }

    private static String headerPath(String header, String prefix) {
        var s = header.substring(prefix.length()).trim();
        if (s.isEmpty()) throw new PatchParseException("Missing path for header: " + prefix);
        return s;
    }

    // ----------------------------- Rendering -----------------------------

    public String renderPatch(List<FileOperation> ops) {
        var sb = new StringBuilder();
        sb.append("*** Begin Patch\n");
        for (var op : ops) {
            sb.append(repr(op)).append("\n");
        }
        sb.append("*** End Patch\n");
        return sb.toString();
    }

    public String repr(EditBlock.FileOperation op) {
        return switch (op) {
            case EditBlock.AddFile add -> {
                var sb = new StringBuilder();
                sb.append("*** Add File: ").append(add.path()).append("\n");
                if (!add.contents().isEmpty()) {
                    var ls = add.contents().split("\n", -1);
                    for (int i = 0; i < ls.length; i++) {
                        var line = ls[i];
                        if (i == ls.length - 1 && line.isEmpty()) break; // trailing sentinel
                        sb.append('+').append(line).append('\n');
                    }
                }
                yield sb.toString().stripTrailing();
            }
            case EditBlock.DeleteFile del -> "*** Delete File: " + del.path();
            case EditBlock.UpdateFile upd -> {
                var sb = new StringBuilder();
                sb.append("*** Update File: ").append(upd.path()).append("\n");
                if (upd.moveTo() != null && !upd.moveTo().isBlank()) {
                    sb.append("*** Move to: ").append(upd.moveTo()).append("\n");
                }
                for (var ch : upd.chunks()) {
                    sb.append(ch.anchor() == null ? "@@\n" : "@@ " + ch.anchor() + "\n");
                    for (var s : ch.preContext()) sb.append(' ').append(s).append('\n');
                    for (var s : ch.oldLines()) sb.append('-').append(s).append('\n');
                    for (var s : ch.newLines()) sb.append('+').append(s).append('\n');
                    for (var s : ch.postContext()) sb.append(' ').append(s).append('\n');
                    if (ch.isEndOfFile()) sb.append("*** End of File\n");
                }
                yield sb.toString().stripTrailing();
            }
        };
    }
}
