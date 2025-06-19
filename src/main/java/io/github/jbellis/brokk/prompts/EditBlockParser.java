package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.util.*;
import java.util.stream.Stream;
import static io.github.jbellis.brokk.gui.mop.stream.flex.EditBlockUtils.*;

public class EditBlockParser {
    public static EditBlockParser instance = new EditBlockParser();

    protected EditBlockParser() {}

    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
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
            """.stripIndent()),
                new UserMessage("Refactor hello() into its own file."),
                new AiMessage("""
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
            """.stripIndent())
        );
    }

    protected final String instructions(String input, String reminder) {
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
        
        <goal>
        %s
        </goal>
        """.stripIndent().formatted(diffFormatInstructions(), reminder, input);
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
        """.stripIndent();
    }

    /**
     * Parses the given content into ParseResult
     */
    public EditBlock.ParseResult parseEditBlocks(String content, Set<ProjectFile> projectFiles) {
        var all = parse(content, projectFiles);
        var editBlocks = all.blocks().stream().map(EditBlock.OutputBlock::block).filter(Objects::nonNull).toList();
        return new EditBlock.ParseResult(editBlocks, all.parseError());
    }

    /**
     * Return vanilla EditBlockParser if `text` doesn't contain anything that looks like one of our markers;
     * if it does, return EditBlockConflictsParser instead.
     */
    public static EditBlockParser getParserFor(String text) {
        return Stream.of("<<<<<", "=====", ">>>>>")
                       .anyMatch(text::contains)
               ? EditBlockConflictsParser.instance
               : EditBlockParser.instance;
    }

    /**
     * Parses the given content into a sequence of OutputBlock records (plain text or edit blocks).
     * Supports a "forgiving" divider approach if we do not see a standard "filename =======" line
     * but do see exactly one line of "=======" in the lines between SEARCH and REPLACE.
     * Malformed blocks do not prevent parsing subsequent blocks.
     */
    public EditBlock.ExtendedParseResult parse(String content, Set<ProjectFile> projectFiles) {
        var blocks = new ArrayList<EditBlock.OutputBlock>();

        var lines = content.split("\n", -1);
        var plain     = new StringBuilder();

        int i = 0;
        String currentFilename = null;

        while (i < lines.length) {
            var trimmed = lines[i].trim();

            /* ----------------------------------------------------------
             * 1.  Block variant that begins with:  ```\n<filename>\n<<<<<<< SEARCH
             * ---------------------------------------------------------- */
            if (trimmed.equals(DEFAULT_FENCE.get(0))
                    && i + 2 < lines.length
                    && HEAD.matcher(lines[i + 2].trim()).matches()) {

                // Flush any accumulated plain‑text that precedes this block
                if (!plain.toString().isBlank()) {
                    blocks.add(EditBlock.OutputBlock.plain(plain.toString()));
                    plain.setLength(0);
                }

                // The filename sits on the line immediately after the opening fence
                var filenameLine   = lines[i + 1];
                var candidatePath  = stripFilename(filenameLine);
                currentFilename       = candidatePath != null && !candidatePath.isBlank()
                                        ? candidatePath
                                        : findFileNameNearby(lines, i + 2, projectFiles, currentFilename);

                // Advance to the <<<<<<< SEARCH marker
                i = i + 2;                                             // now at HEAD line
                i++;                                                   // move past HEAD

                var beforeLines = new ArrayList<String>();
                while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    beforeLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new EditBlock.ExtendedParseResult(blocks,
                                                             "Expected ======= divider after <<<<<<< SEARCH");
                }

                i++;   // skip ======= divider

                var afterLines = new ArrayList<String>();
                while (i < lines.length
                        && !UPDATED.matcher(lines[i].trim()).matches()
                        && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    afterLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new EditBlock.ExtendedParseResult(blocks,
                                                             "Expected >>>>>>> REPLACE or =======");
                }

                var beforeJoined = stripQuotedWrapping(String.join("\n", beforeLines),
                                                          currentFilename);
                var afterJoined  = stripQuotedWrapping(String.join("\n", afterLines),
                                                          currentFilename);

                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                if (!afterJoined.isEmpty()  && !afterJoined.endsWith("\n"))  afterJoined  += "\n";

                blocks.add(EditBlock.OutputBlock.edit(
                        new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined)));

                // Consume the >>>>>>> REPLACE marker (if present)
                if (UPDATED.matcher(lines[i].trim()).matches()) {
                    i++;
                }
                // Consume the closing ``` fence (if present)
                if (i < lines.length && lines[i].trim().equals(DEFAULT_FENCE.get(0))) {
                    i++;
                }
                continue;   // restart main loop
            }

            /* ----------------------------------------------------------
             * 2.  Legacy / fence‑less variant that starts directly with <<<<<<< SEARCH
             * ---------------------------------------------------------- */
            if (HEAD.matcher(trimmed).matches()) {
                if (!plain.toString().isBlank()) {
                    blocks.add(EditBlock.OutputBlock.plain(plain.toString()));
                    plain.setLength(0);
                }

                currentFilename = findFileNameNearby(lines, i, projectFiles, currentFilename);

                i++;   // move past <<<<<<< SEARCH
                var beforeLines = new ArrayList<String>();
                while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    beforeLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new EditBlock.ExtendedParseResult(blocks,
                                                             "Expected ======= divider after <<<<<<< SEARCH");
                }

                i++;   // skip ======= divider

                var afterLines = new ArrayList<String>();
                while (i < lines.length
                        && !UPDATED.matcher(lines[i].trim()).matches()
                        && !DIVIDER.matcher(lines[i].trim()).matches()) {
                    afterLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new EditBlock.ExtendedParseResult(blocks,
                                                             "Expected >>>>>>> REPLACE or =======");
                }

                var beforeJoined = stripQuotedWrapping(String.join("\n", beforeLines),
                                                          currentFilename);
                var afterJoined  = stripQuotedWrapping(String.join("\n", afterLines),
                                                          currentFilename);

                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                if (!afterJoined.isEmpty()  && !afterJoined.endsWith("\n"))  afterJoined  += "\n";

                blocks.add(EditBlock.OutputBlock.edit(
                        new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined)));

                // Consume the >>>>>>> REPLACE marker (if present)
                if (UPDATED.matcher(lines[i].trim()).matches()) {
                    i++;
                }
                // Optional closing fence for this form
                if (i < lines.length && lines[i].trim().equals(DEFAULT_FENCE.get(0))) {
                    i++;
                }
                continue;
            }

            /* ----------------------------------------------------------
             * 3.  Not part of an edit block — accumulate plain text
             * ---------------------------------------------------------- */
            plain.append(lines[i]);
            if (i < lines.length - 1) {
                plain.append("\n");
            }
            i++;
        }

        // Flush any trailing plain text
        if (!plain.toString().isBlank()) {
            blocks.add(EditBlock.OutputBlock.plain(plain.toString()));
        }

        return new EditBlock.ExtendedParseResult(blocks, null);
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
               """.formatted(DEFAULT_FENCE.get(0),
                             block.filename(),
                             beforeText,
                             beforeText.endsWith("\n") ? "" : "\n",
                             afterText,
                             afterText.endsWith("\n") ? "" : "\n",
                             DEFAULT_FENCE.get(1));
    }
}
