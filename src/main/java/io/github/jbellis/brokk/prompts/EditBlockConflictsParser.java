package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class EditBlockConflictsParser extends EditBlockParser {
    public static EditBlockConflictsParser instance = new EditBlockConflictsParser();

    private EditBlockConflictsParser() {
        super();
    }

    @Override
    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
                  To make this change we need to modify `mathweb/flask/app.py` to:
                  
                  1. Import the math package.
                  2. Remove the existing factorial() function.
                  3. Update get_factorial() to call math.factorial instead.
                  
                  Here are the *SEARCH/REPLACE* blocks:

                  <<<<<<< SEARCH mathweb/flask/app.py
                  from flask import Flask
                  ======= mathweb/flask/app.py
                  import math
                  from flask import Flask
                  >>>>>>> REPLACE mathweb/flask/app.py

                  <<<<<<< SEARCH mathweb/flask/app.py
                  def factorial(n):
                      "compute factorial"
                      if n == 0:
                          return 1
                      else:
                          return n * factorial(n-1)
                  ======= mathweb/flask/app.py
                  >>>>>>> REPLACE mathweb/flask/app.py

                  <<<<<<< SEARCH mathweb/flask/app.py
                      return str(factorial(n))
                  ======= mathweb/flask/app.py
                      return str(math.factorial(n))
                  >>>>>>> REPLACE mathweb/flask/app.py
                  """.stripIndent()),
                new UserMessage("Refactor hello() into its own filename."),
                new AiMessage("""
                  To make this change we need to modify `main.py` and make a new filename `hello.py`:

                  1. Make a new hello.py filename with hello() in it.
                  2. Remove hello() from main.py and replace it with an import.

                  Here are the *SEARCH/REPLACE* blocks:
                  <<<<<<< SEARCH hello.py
                  ======= hello.py
                  def hello():
                      "print a greeting"
                      print("hello")
                  >>>>>>> REPLACE hello.py

                  <<<<<<< SEARCH main.py
                  def hello():
                      "print a greeting"
                      print("hello")
                  ======= main.py
                  from hello import hello
                  >>>>>>> REPLACE main.py
                  """.stripIndent())
        );
    }

    @Override
    public String diffFormatInstructions() {
        return """
        # *SEARCH/REPLACE blocks*

        *SEARCH/REPLACE* blocks describe how to edit files. They are composed of
        3 delimiting markers. Each marker repeats the FULL filename being edited.
        For example,
        <<<<<<<< SEARCH io/github/jbellis/Foo.java
        ======== io/github/jbellis/Foo.java
        >>>>>>>> REPLACE io/github/jbellis/Foo.java
        
        These markers (the hardcoded tokens, plus the filename) are referred to as the search, dividing,
        and replace markers, respectively.

        Every *SEARCH/REPLACE* block must use this format:
        1. The search marker, followed by the full filename: <<<<<<< SEARCH $filename
        2. A contiguous chunk of lines to search for in the existing source code
        3. The dividing marker, followed by the full filename: ======= $filename
        4. The lines to replace in the source code
        5. The replace marker, followed by the full filename: >>>>>>> REPLACE $filename

        Use the *FULL* filename, as shown to you by the user. This appears on each of the three marker lines.
        No other text should appear on the marker lines.
        
        You should expect to encounter git merge conflict markers in the files you are editing. These
        bear some resemblance to your *SEARCH/REPLACE* blocks. To avoid confusion, ALWAYS remember to
        include the filename after the *SEARCH/REPLACE* delimiters, including the dividing marker!
        """.stripIndent();
    }

    // Pattern for the start of a search block, capturing the filename
    private static final Pattern SEARCH = Pattern.compile("^\\s*<{5,9} SEARCH\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the divider, capturing the filename
    private static final Pattern DIVIDER = Pattern.compile("^\\s*={5,9}\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the end of a replace block, capturing the filename
    private static final Pattern REPLACE = Pattern.compile("^\\s*>{5,9} REPLACE\\s*(\\S+?)\\s*$", Pattern.MULTILINE);

    public EditBlock.ExtendedParseResult parse(String content) {
        return parse(content, null);
    }

    @Override
    public EditBlock.ExtendedParseResult parse(String content, Set<ProjectFile> unused) {
        var outputBlocks = new ArrayList<EditBlock.OutputBlock>();
        var parseErrors = new StringBuilder();
        var leftoverText = new StringBuilder();

        String[] lines = content.split("\n", -1);
        int i = 0;

        outerLoop:
        while (i < lines.length) {
            // 1) Look for "<<<<<<< SEARCH filename"
            var headMatcher = SEARCH.matcher(lines[i]);
            if (!headMatcher.matches()) {
                // Not a SEARCH line => treat as plain text
                leftoverText.append(lines[i]);
                if (i < lines.length - 1) {
                    leftoverText.append("\n");
                }
                i++;
                continue;
            }

            // We found a SEARCH line
            String currentFilename = headMatcher.group(1).trim();
            int searchLineIndex = i;
            i++;

            var beforeLines = new ArrayList<String>();
            boolean blockSuccess = false;

            blockLoop:
            while (i < lines.length) {
                var dividerMatcher = DIVIDER.matcher(lines[i]);
                var replaceMatcher = REPLACE.matcher(lines[i]);

                // 2a) If we encounter a "filename =======" line, gather "after" lines until REPLACE
                if (dividerMatcher.matches() && dividerMatcher.group(1).trim().equals(currentFilename)) {
                    var afterLines = new ArrayList<String>();
                    i++; // skip the divider line

                    int replaceIndex = -1;
                    while (i < lines.length) {
                        var r2 = REPLACE.matcher(lines[i]);
                        var divider2 = DIVIDER.matcher(lines[i]);

                        if (r2.matches() && r2.group(1).trim().equals(currentFilename)) {
                            replaceIndex = i;
                            break;
                        } else if (divider2.matches() && divider2.group(1).trim().equals(currentFilename)) {
                            // A second named divider => parse error
                            parseErrors.append("Multiple named dividers found for ")
                                    .append(currentFilename).append(" block.\n");

                            // Revert everything
                            revertLinesToLeftover(leftoverText,
                                                  lines[searchLineIndex],
                                                  beforeLines,
                                                  afterLines);
                            leftoverText.append(lines[i]).append("\n");
                            i++;
                            continue outerLoop;
                        }

                        afterLines.add(lines[i]);
                        i++;
                    }

                    if (replaceIndex < 0) {
                        // We never found "filename >>>>>>> REPLACE"
                        parseErrors.append("Expected '")
                                .append(currentFilename)
                                .append(" >>>>>>> REPLACE' marker.\n");
                        revertLinesToLeftover(leftoverText,
                                              lines[searchLineIndex],
                                              beforeLines,
                                              afterLines);
                        continue outerLoop;
                    }

                    // We found a valid block
                    i++; // skip the REPLACE line
                    String beforeJoined = String.join("\n", beforeLines);
                    String afterJoined = String.join("\n", afterLines);
                    if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                        beforeJoined += "\n";
                    }
                    if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                        afterJoined += "\n";
                    }

                    // flush leftover text so it becomes a separate plain block
                    flushLeftoverText(leftoverText, outputBlocks);

                    var block = new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined);
                    outputBlocks.add(EditBlock.OutputBlock.edit(block));
                    blockSuccess = true;
                    break blockLoop;
                }

                // 2b) If we encounter "filename >>>>>>> REPLACE" *before* a divider,
                // we do the single-line "======" fallback approach.
                if (replaceMatcher.matches() && replaceMatcher.group(1).trim().equals(currentFilename)) {
                    // Attempt the fallback approach
                    int partialCount = 0;
                    int partialIdx = -1;
                    for (int idx = 0; idx < beforeLines.size(); idx++) {
                        if (beforeLines.get(idx).matches("^\\s*={5,9}\\s*$")) {
                            partialCount++;
                            partialIdx = idx;
                        }
                    }
                    if (partialCount == 1) {
                        String beforeJoined = String.join("\n", beforeLines.subList(0, partialIdx));
                        String afterJoined = String.join("\n", beforeLines.subList(partialIdx + 1, beforeLines.size()));
                        if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                            beforeJoined += "\n";
                        }
                        if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                            afterJoined += "\n";
                        }

                        flushLeftoverText(leftoverText, outputBlocks);
                        var srBlock = new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined);
                        outputBlocks.add(EditBlock.OutputBlock.edit(srBlock));

                        blockSuccess = true;
                        i++; // skip the REPLACE line
                        break blockLoop;
                    } else {
                        parseErrors.append("Failed to parse block for '")
                                .append(currentFilename)
                                .append("': found ")
                                .append(partialCount)
                                .append(" standalone '=======' lines.\n");

                        revertLinesToLeftover(leftoverText,
                                              lines[searchLineIndex],
                                              beforeLines,
                                              lines[i]);
                        i++;
                        continue outerLoop;
                    }
                }

                // If it's neither a recognized divider nor REPLACE => accumulate in beforeLines
                beforeLines.add(lines[i]);
                i++;
            }

            // If we exit the while loop normally, we never found a divider or fallback => parse error
            if (!blockSuccess) {
                parseErrors.append("Expected '")
                        .append(currentFilename)
                        .append(" =======' divider after '")
                        .append(currentFilename)
                        .append(" <<<<<<< SEARCH' but not found.\n");
                revertLinesToLeftover(leftoverText, lines[searchLineIndex], beforeLines, (String) null);
            }
        }

        // After processing all lines, flush leftover text
        flushLeftoverText(leftoverText, outputBlocks);

        String errorText = parseErrors.isEmpty() ? null : parseErrors.toString();
        return new EditBlock.ExtendedParseResult(outputBlocks, errorText);
    }

    /**
     * If leftover text is non-blank, add it as a plain-text block; then reset it.
     */
    private static void flushLeftoverText(StringBuilder leftover, List<EditBlock.OutputBlock> outputBlocks) {
        var text = leftover.toString();
        if (!text.isBlank()) {
            outputBlocks.add(EditBlock.OutputBlock.plain(text));
        }
        leftover.setLength(0);
    }

    /**
     * Reverts the lines belonging to a malformed block back into leftover text (as plain text),
     * including the original "SEARCH filename" line, any collected lines, and an optional trailing line.
     */
    private static void revertLinesToLeftover(StringBuilder leftover,
                                              String searchLine,
                                              List<String> collectedLines,
                                              String trailingLine)
    {
        leftover.append(searchLine).append("\n");
        for (var ln : collectedLines) {
            leftover.append(ln).append("\n");
        }
        if (trailingLine != null) {
            leftover.append(trailingLine).append("\n");
        }
    }

    /**
     * Reverts the lines belonging to a malformed block back into leftover text,
     * including the original "SEARCH filename" line, any collected lines, and
     * a list of "afterLines".
     */
    private static void revertLinesToLeftover(StringBuilder leftover,
                                              String searchLine,
                                              List<String> beforeLines,
                                              List<String> afterLines)
    {
        leftover.append(searchLine).append("\n");
        for (var ln : beforeLines) {
            leftover.append(ln).append("\n");
        }
        if (afterLines != null) {
            for (var ln : afterLines) {
                leftover.append(ln).append("\n");
            }
        }
    }

    @Override
    public String repr(EditBlock.SearchReplaceBlock block) {
        StringBuilder sb = new StringBuilder();
        sb.append("<<<<<<< SEARCH %s\n".formatted(block.filename()));
        sb.append(block.beforeText());
        if (!block.beforeText().endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("======= %s\n".formatted(block.filename()));
        sb.append(block.afterText());
        if (!block.afterText().endsWith("\n")) {
            sb.append("\n");
        }
        sb.append(">>>>>>> REPLACE %s\n".formatted(block.filename()));

        return sb.toString();
    }
}
