package io.github.jbellis.brokk.prompts;

import static io.github.jbellis.brokk.prompts.EditBlockUtils.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.*;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

public class EditBlockParser {
    public static EditBlockParser instance = new EditBlockParser();

    protected EditBlockParser() {}

    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage(
                        """
            To make this change we need to modify `mathweb/flask/app.py` to:

            1. Import the math package.
            2. Remove the existing factorial() function.
            3. Update get_factorial() to call math.factorial instead.

            Here are the *SEARCH/REPLACE* blocks:

            ```
            mathweb/flask/app.py
            <<<<<<< SEARCH
            from flask import Flask
            =======
            import math
            from flask import Flask
            >>>>>>> REPLACE
            ```

            ```
            mathweb/flask/app.py
            <<<<<<< SEARCH
            def factorial(n):
                "compute factorial"

                if n == 0:
                    return 1
                else:
                    return n * factorial(n-1)
            =======
            >>>>>>> REPLACE
            ```

            ```
            mathweb/flask/app.py
            <<<<<<< SEARCH
                return str(factorial(n))
            =======
                return str(math.factorial(n))
            >>>>>>> REPLACE
            ```
            """
                                .stripIndent()),
                new UserMessage("Refactor hello() into its own file."),
                new AiMessage(
                        """
            To make this change we need to modify `main.py` and make a new file `hello.py`:

            1. Make a new hello.py file with hello() in it.
            2. Remove hello() from main.py and replace it with an import.

            Here are the *SEARCH/REPLACE* blocks:

            ```
            hello.py
            <<<<<<< SEARCH
            =======
            def hello():
                "print a greeting"

                print("hello")
            >>>>>>> REPLACE
            ```

            ```
            main.py
            <<<<<<< SEARCH
            def hello():
                "print a greeting"

                print("hello")
            =======
            from hello import hello
            >>>>>>> REPLACE
            ```
            """
                                .stripIndent()));
    }

    protected final String instructions(String input, @Nullable ProjectFile file, String reminder) {
        return """
        <rules>
        %s

        Every *SEARCH* block must *EXACTLY MATCH* the existing filename content, character for character,
        including all comments, docstrings, indentation, etc.
        If the file contains code or other data wrapped in json/xml/quotes or other containers,
        you need to propose edits to the literal contents, including that container markup.

        *SEARCH* and *REPLACE* blocks must both contain ONLY the lines to be matched or edited.
        This means no +/- diff markers in particular!

        *SEARCH/REPLACE* blocks will *fail* to apply if the SEARCH text matches multiple occurrences.
        Include enough lines to uniquely match each set of lines that need to change.

        Keep *SEARCH/REPLACE* blocks concise.
        Break large changes into a series of smaller blocks that each change a small portion.
        Include just the changing lines, plus a few surrounding lines if needed for uniqueness.
        You should not need to include the entire function or block to change a line or two.

        Avoid generating overlapping *SEARCH/REPLACE* blocks, combine them into a single edit.

        If you want to move code within a filename, use 2 blocks: one to delete from the old location,
        and one to insert in the new location.

        Pay attention to which filenames the user wants you to edit, especially if they are asking
        you to create a new filename.

        Important! To create a new file OR to replace an *entire* existing file, use a *SEARCH/REPLACE*
        block with nothing in between the search and divider marker lines, and the new file's full contents between
        the divider and replace marker lines. Rule of thumb: replace the entire file if you will need to
        change more than half of it.

        If the user just says something like "ok" or "go ahead" or "do that", they probably want you
        to make SEARCH/REPLACE blocks for the code changes you just proposed.
        The user will say when they've applied your edits.
        If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.

        NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
        use vanilla ascii single and double quotes.

        # General
        Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.

        Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!

        %s
        </rules>

        <goal%s>
        %s
        </goal>
        """
                .formatted(
                        diffFormatInstructions(),
                        reminder,
                        file == null ? "" : " target=\"%s\">".formatted(file),
                        input);
    }

    public String diffFormatInstructions() {
        return """
        # *SEARCH/REPLACE block* Rules:

        Every *SEARCH/REPLACE block* must use this format:
        1. The opening fence: ```
        2. The *FULL* file path alone on a line, verbatim. No bold asterisks, no quotes around it, no escaping of characters, etc.
        3. The start of search block: <<<<<<< SEARCH
        4. A contiguous chunk of lines to search for in the existing source code
        5. The dividing line: =======
        6. The lines to replace into the source code
        7. The end of the replace block: >>>>>>> REPLACE
        8. The closing fence: ```

        Use the *FULL* file path, as shown to you by the user. No other text should appear on the marker lines.
        """
                .stripIndent();
    }

    /** Single internal scanner used by both parseEditBlocks and redact */
    @VisibleForTesting
    public interface BlockScanHandler {
        void onPlainText(String text);
        void onBlock(EditBlock.SearchReplaceBlock block);
        void onError(String message, String rawFragment);
    }

    @VisibleForTesting
    public void scan(String content, Set<ProjectFile> projectFiles, BlockScanHandler handler) {
        var lines = content.split("\n", -1);
        var plain = new StringBuilder();

        int i = 0;
        String currentFilename = null;

        while (i < lines.length) {
            var trimmed = lines[i].trim();

            // 1) Fenced variant: ``` \n <filename> \n <<<<<<< SEARCH
            if (trimmed.equals(DEFAULT_FENCE.get(0))
                    && i + 2 < lines.length
                    && HEAD.matcher(lines[i + 2].trim()).matches()) {

                // flush preceding plain text
                if (!plain.toString().isBlank()) {
                    handler.onPlainText(plain.toString());
                    plain.setLength(0);
                }

                int blockStart = i;
                var filenameLine = lines[i + 1];
                var candidatePath = stripFilename(filenameLine);
                currentFilename = candidatePath != null && !candidatePath.isBlank()
                        ? candidatePath
                        : findFileNameNearby(lines, i + 2, projectFiles, currentFilename);

                // advance to HEAD line and beyond
                i = i + 2;
                i++;

                var beforeLines = new ArrayList<String>();
                while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    beforeLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    var raw = joinRange(lines, blockStart, lines.length);
                    handler.onError("Expected ======= divider after <<<<<<< SEARCH", raw);
                    // treat as plain text
                    plain.append(raw);
                    break;
                }

                i++; // skip ======= divider

                var afterLines = new ArrayList<String>();
                while (i < lines.length
                        && !UPDATED.matcher(lines[i].trim()).matches()
                        && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    afterLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    var raw = joinRange(lines, blockStart, lines.length);
                    handler.onError("Expected >>>>>>> REPLACE or =======", raw);
                    plain.append(raw);
                    break;
                }

                var beforeJoined =
                        stripQuotedWrapping(String.join("\n", beforeLines), Objects.toString(currentFilename, ""));
                var afterJoined =
                        stripQuotedWrapping(String.join("\n", afterLines), Objects.toString(currentFilename, ""));

                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) afterJoined += "\n";

                handler.onBlock(new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined));

                // consume >>>>>>> REPLACE if present
                if (UPDATED.matcher(lines[i].trim()).matches()) {
                    i++;
                }
                // consume closing fence if present
                if (i < lines.length && lines[i].trim().equals(DEFAULT_FENCE.get(0))) {
                    i++;
                }
                continue;
            }

            // 2) Fence-less variant that starts directly with <<<<<<< SEARCH
            if (HEAD.matcher(trimmed).matches()) {
                if (!plain.toString().isBlank()) {
                    handler.onPlainText(plain.toString());
                    plain.setLength(0);
                }

                int blockStart = i;
                currentFilename = findFileNameNearby(lines, i, projectFiles, currentFilename);

                i++; // move past HEAD
                var beforeLines = new ArrayList<String>();
                while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    beforeLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    var raw = joinRange(lines, blockStart, lines.length);
                    handler.onError("Expected ======= divider after <<<<<<< SEARCH", raw);
                    plain.append(raw);
                    break;
                }

                i++; // skip ======= divider

                var afterLines = new ArrayList<String>();
                while (i < lines.length
                        && !UPDATED.matcher(lines[i].trim()).matches()
                        && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    afterLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    var raw = joinRange(lines, blockStart, lines.length);
                    handler.onError("Expected >>>>>>> REPLACE or =======", raw);
                    plain.append(raw);
                    break;
                }

                var beforeJoined =
                        stripQuotedWrapping(String.join("\n", beforeLines), Objects.toString(currentFilename, ""));
                var afterJoined =
                        stripQuotedWrapping(String.join("\n", afterLines), Objects.toString(currentFilename, ""));

                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) afterJoined += "\n";

                handler.onBlock(new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined));

                // consume >>>>>>> REPLACE if present
                if (UPDATED.matcher(lines[i].trim()).matches()) {
                    i++;
                }
                // optional closing fence for this form
                if (i < lines.length && lines[i].trim().equals(DEFAULT_FENCE.get(0))) {
                    i++;
                }
                continue;
            }

            // 3) Not part of block — accumulate plain text
            plain.append(lines[i]);
            if (i < lines.length - 1) {
                plain.append("\n");
            }
            i++;
        }

        // Flush any trailing plain text
        if (!plain.toString().isBlank()) {
            handler.onPlainText(plain.toString());
        }
    }

    private static String joinRange(String[] lines, int start, int end) {
        var sb = new StringBuilder();
        for (int j = start; j < end; j++) {
            sb.append(lines[j]);
            if (j < end - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /** Parses the given content into ParseResult (SRBs only) */
    public EditBlock.ParseResult parse(String content, Set<ProjectFile> projectFiles) {
        var blocks = new ArrayList<EditBlock.SearchReplaceBlock>();
        String[] firstError = new String[1]; // null if none

        scan(content, projectFiles, new BlockScanHandler() {
            @Override
            public void onPlainText(String text) {
                // ignore (SRBs only)
            }

            @Override
            public void onBlock(EditBlock.SearchReplaceBlock block) {
                blocks.add(block);
            }

            @Override
            public void onError(String message, String rawFragment) {
                if (firstError[0] == null) firstError[0] = message;
            }
        });

        String error = firstError[0];
        if (blocks.isEmpty() && error == null && Stream.of("<<<<<", "=====", ">>>>>").anyMatch(content::contains)) {
            error = "It looks like you tried to include an edit block, but I couldn't parse it.";
        }
        return new EditBlock.ParseResult(blocks, error);
    }

    /**
     * Redacts SEARCH/REPLACE blocks from the provided text, preserving the surrounding plaintext.
     * If no blocks are found, returns the original text unchanged.
     */
    public String redact(String original) {
        final String placeholder = "[elided SEARCH/REPLACE block]";
        var out = new StringBuilder();
        boolean[] hadBlocks = new boolean[] {false};
        boolean[] prevWasBlock = new boolean[] {false};

        scan(original, Collections.emptySet(), new BlockScanHandler() {
            @Override
            public void onPlainText(String text) {
                out.append(text);
                prevWasBlock[0] = false;
            }

            @Override
            public void onBlock(EditBlock.SearchReplaceBlock block) {
                if (prevWasBlock[0]) {
                    out.append('\n');
                }
                out.append(placeholder);
                prevWasBlock[0] = true;
                hadBlocks[0] = true;
            }

            @Override
            public void onError(String message, String rawFragment) {
                // Treat malformed constructs as plain text
                out.append(rawFragment);
                prevWasBlock[0] = false;
            }
        });

        return hadBlocks[0] ? out.toString() : original;
    }

    public String repr(EditBlock.SearchReplaceBlock block) {
        var beforeText = block.beforeText();
        var afterText = block.afterText();
        return """
               %s
               %s
               <<<<<<< SEARCH
               %s%s
               =======
               %s%s
               >>>>>>> REPLACE
               %s
               """
                .formatted(
                        DEFAULT_FENCE.get(0),
                        block.rawFileName(),
                        beforeText,
                        beforeText.endsWith("\n") ? "" : "\n",
                        afterText,
                        afterText.endsWith("\n") ? "" : "\n",
                        DEFAULT_FENCE.get(1));
    }
}
