package io.github.jbellis.brokk;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility for extracting and applying before/after search-replace blocks in content.
 */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

    private EditBlock() {
        // utility class
    }

    /**
     * Helper that returns the first code block found between triple backticks.
     * Skips any text on the same line as the opening backticks (like language specifiers)
     * and starts capturing from the next line.
     * Returns an empty string if no valid block is found.
     */
    public static String extractCodeFromTripleBackticks(String text) {
        // Pattern: ``` followed by optional non-newline chars, then newline, then capture until ```
        var matcher = Pattern.compile(
                "```[^\\n]*\\n(.*?)```", // Skip first line, capture starting from the second
                Pattern.DOTALL
        ).matcher(text);

        if (matcher.find()) {
            // group(1) captures the content between the newline and the closing ```
            return matcher.group(1);
        }
        return "";
    }

    public enum EditBlockFailureReason {
        FILE_NOT_FOUND,
        NO_MATCH,
        AMBIGUOUS_MATCH,
        IO_ERROR
    }

    public record EditResult(Map<ProjectFile, String> originalContents, List<FailedBlock> failedBlocks) {
        public boolean hadSuccessfulEdits() {
            return !originalContents.isEmpty();
        }
    }

    public record FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason, String commentary) {
        public FailedBlock {
            assert block != null;
            assert reason != null;
            assert commentary != null;
        }

        public FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) {
            this(block, reason, "");
        }
    }

    // -- Exceptions for file resolution --

    /**
     * Thrown when a filename provided by the LLM cannot be uniquely resolved.
     */
    public static class SymbolNotFoundException extends Exception {
        public SymbolNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a filename provided by the LLM matches multiple files.
     */
    public static class SymbolAmbiguousException extends Exception {
        public SymbolAmbiguousException(String message) {
            super(message);
        }
    }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks and apply them.
     *
     * Note: it is the responsibility of the caller (e.g. CodeAgent::preCreateNewFiles)
     * to create empty files for blocks corresponding to new files.
     */
    public static EditResult applyEditBlocks(IContextManager contextManager, IConsoleIO io, Collection<SearchReplaceBlock> blocks)
    throws IOException
    {
        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        Map<SearchReplaceBlock, ProjectFile> succeeded = new HashMap<>();

        // Track original file contents before any changes
        Map<ProjectFile, String> changedFiles = new HashMap<>();

        for (SearchReplaceBlock block : blocks) {
            // 1. Resolve the filename
            ProjectFile file;
            try {
                if (block.filename() == null || block.filename().isBlank()) {
                    throw new SymbolNotFoundException("Block is missing filename");
                }
                file = resolveProjectFile(contextManager, block.filename());
            } catch (SymbolNotFoundException | SymbolAmbiguousException e) {
                logger.debug("File resolution failed for block [{}]: {}", block.filename(), e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.FILE_NOT_FOUND));
                continue; // Skip to the next block
            }

            // 2. Apply the edit using replaceInFile
            try {
                // Save original content before attempting change
                if (!changedFiles.containsKey(file)) {
                    changedFiles.put(file, file.exists() ? file.read() : "");
                }

                // Perform the replacement
                replaceInFile(file, block.beforeText(), block.afterText(), contextManager);

                // add to succeeded list
                // If it was a deletion, replaceInFile handled it and returned; file will not exist.
                // If it was a modification or creation, the file will exist with new content.
                succeeded.put(block, file);
            } catch(NoMatchException | AmbiguousMatchException e) {
                assert changedFiles.containsKey(file);
                var originalContent = changedFiles.get(file);
                String commentary;
                try {
                    replaceMostSimilarChunk(originalContent, block.afterText, "");
                    // if it didn't throw:
                    commentary = """
                                 The replacement text is already present in the file. If we no longer need to apply
                                 this block, omit it from your reply.
                                 """.stripIndent();
                } catch (NoMatchException | AmbiguousMatchException e2) {
                    commentary = "";
                }

                // Check if the search block looks like a diff
                if (block.beforeText().lines().anyMatch(line -> line.startsWith("-") || line.startsWith("+"))) {
                    commentary += """
                                  Reminder: Brokk uses SEARCH/REPLACE blocks, not unified diff format.
                                  Ensure the `<<<<<<< SEARCH $filename` block matches the existing code exactly.
                                  """.stripIndent();
                }

                logger.debug("Edit application failed for file [{}] {}: {}", file, e.getClass().getSimpleName(), e.getMessage());
                var reason = e instanceof NoMatchException ? EditBlockFailureReason.NO_MATCH : EditBlockFailureReason.AMBIGUOUS_MATCH;
                failed.add(new FailedBlock(block, reason, commentary));
            } catch (IOException e) {
                var msg = "Error applying edit to " + file;
                logger.error("{}: {}", msg, e.getMessage());
                throw new IOException(msg);
            } catch (GitAPIException e) {
                var msg = "Non-fatal error: unable to update `%s` in Git".formatted(file);
                logger.error("{}: {}", msg, e.getMessage());
                io.systemOutput(msg);
            }
        }

        changedFiles.keySet().retainAll(succeeded.values());
        return new EditResult(changedFiles, failed);
    }

    /**
     * Simple record storing the parts of a search-replace block.
     * If {@code filename} is non-null, then this block corresponds to a filename’s
     * search/replace
     */
    public record SearchReplaceBlock(@Nullable String filename, String beforeText, String afterText) {
        public SearchReplaceBlock {
            // filename can be null on bad parse
            assert beforeText != null;
            assert afterText != null;
        }
    }

    public record ParseResult(List<SearchReplaceBlock> blocks, String parseError) {
    }

    public record ExtendedParseResult(List<OutputBlock> blocks, String parseError) {
    }

    /**
     * Represents a segment of the LLM output, categorized as either plain text or a parsed Edit Block.
     */
    public record OutputBlock(@Nullable String text, @Nullable SearchReplaceBlock block) {
        /**
         * Ensures that exactly one of the fields is non-null.
         */
        public OutputBlock {
            assert (text == null) != (block == null);
        }

        /**
         * Convenience constructor for plain text blocks.
         */
        public static OutputBlock plain(String text) {
            return new OutputBlock(text, null);
        }

        /**
         * Convenience constructor for Edit blocks.
         */
        public static OutputBlock edit(SearchReplaceBlock block) {
            return new OutputBlock(null, block);
        }
    }

    /**
     * Determines if an edit operation constitutes a logical deletion of file content.
     * A deletion occurs if the original content was non-blank and the updated content becomes blank.
     *
     * @param originalContent The content of the file before the edit.
     * @param updatedContent The content of the file after the edit.
     * @return {@code true} if the operation is a logical deletion, {@code false} otherwise.
     */
    static boolean isDeletion(String originalContent, String updatedContent) {
        // 1. The original must have had *something* non-blank
        if (originalContent.isBlank()) {
            return false;
        }
        // 2. After replacement the file is blank (only whitespace/newlines)
        return updatedContent.isBlank();
    }

    /**
     * Replace the first occurrence of `beforeText` lines with `afterText` lines in the given file.
     * If the operation results in the file becoming blank (and it wasn't already), the file is deleted
     * and the deletion is staged in Git via the contextManager.
     * Throws NoMatchException if `beforeText` is not found in the file content.
     * Throws AmbiguousMatchException if more than one match is found.
     */
    public static void replaceInFile(ProjectFile file,
                                     String beforeText,
                                     String afterText,
                                     IContextManager contextManager)
    throws IOException, NoMatchException, AmbiguousMatchException, GitAPIException
    {
        String original = file.exists() ? file.read() : "";
        String updated = replaceMostSimilarChunk(original, beforeText, afterText);

        if (isDeletion(original, updated)) {
            logger.info("Detected deletion for file {}", file);
            java.nio.file.Files.deleteIfExists(file.absPath()); // remove from disk
            contextManager.getRepo().remove(file); // stage deletion
            return; // Do not write the blank content
        }

        file.write(updated);
    }

    /**
     * Custom exception thrown when no matching location is found in the file.
     */
    public static class NoMatchException extends Exception {
        public NoMatchException(String msg) {
            super(msg);
        }
    }

    /**
     * Thrown when more than one matching location is found.
     */
    public static class AmbiguousMatchException extends Exception {
        public AmbiguousMatchException(String message) {
            super(message);
        }
    }

    /**
     * Attempts perfect/whitespace replacements, then tries "...", then fuzzy.
     * Returns the post-replacement content, or null if no match found.
     * Throws AmbiguousMatchException if multiple matches are found.
     */
    static String replaceMostSimilarChunk(String content, String target, String replace)
    throws AmbiguousMatchException, NoMatchException
    {
        // 1) prep for line-based matching
        ContentLines originalCL = prep(content);
        ContentLines targetCl = prep(target);
        ContentLines replaceCL = prep(replace);

        // 2) perfect or whitespace approach
        // Convert List<String> to String[] for perfectOrWhitespace and other methods
        String[] originalLinesArray = originalCL.lines().toArray(String[]::new);
        String[] targetLinesArray = targetCl.lines().toArray(String[]::new);
        String[] replaceLinesArray = replaceCL.lines().toArray(String[]::new);
        String attempt = perfectOrWhitespace(originalLinesArray, targetLinesArray, replaceLinesArray);
        if (attempt != null) {
            return attempt;
        }

        // 3) handle triple-dot expansions
        try {
            attempt = tryDotdotdots(content, target, replace);
            if (attempt != null) {
                return attempt;
            }
        } catch (IllegalArgumentException e) {
            // ignore if it fails
        }

        // 3a) If that failed, attempt dropping a spurious leading blank line from the "search" block:
        if (targetCl.lines().size() > 2 && targetCl.lines().getFirst().trim().isEmpty()) {
            List<String> splicedTargetList = targetCl.lines().subList(1, targetCl.lines().size());
            List<String> splicedReplaceList = replaceCL.lines().subList(1, replaceCL.lines().size());

            String[] splicedTargetArray = splicedTargetList.toArray(String[]::new);
            String[] splicedReplaceArray = splicedReplaceList.toArray(String[]::new);

            // Pass originalLinesArray which is String[]
            attempt = perfectOrWhitespace(originalLinesArray, splicedTargetArray, splicedReplaceArray);
            if (attempt != null) {
                return attempt;
            }

            attempt = tryDotdotdots(content,
                                    String.join("", splicedTargetArray),
                                    String.join("", splicedReplaceArray));
            if (attempt != null) {
                return attempt;
            }
        }

        throw new NoMatchException("No matching oldLines found in content");
    }

    /**
     * Counts how many leading lines in 'lines' are completely blank (trim().isEmpty()).
     */
    static int countLeadingBlankLines(String[] lines) {
        int c = 0;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) {
                c++;
            } else {
                break;
            }
        }
        return c;
    }

    /**
     * If the search/replace has lines of "..." as placeholders, do naive partial replacements.
     */
    public static String tryDotdotdots(String whole, String target, String replace) throws NoMatchException {
        // If there's no "..." in target or whole, skip
        if (!target.contains("...") && !whole.contains("...")) {
            return null;
        }
        // splits on lines of "..."
        Pattern dotsRe = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s*$");

        List<String> targetPieces = Splitter.on(dotsRe).splitToList(target);
        List<String> replacePieces = Splitter.on(dotsRe).splitToList(replace);

        if (targetPieces.size() != replacePieces.size()) {
            throw new IllegalArgumentException("Unpaired ... usage in search/replace");
        }

        String result = whole;
        for (int i = 0; i < targetPieces.size(); i++) {
            String pp = targetPieces.get(i);
            String rp = replacePieces.get(i);

            if (pp.isEmpty() && rp.isEmpty()) {
                // no content
                continue;
            }
            if (!pp.isEmpty()) {
                if (!result.contains(pp)) {
                    throw new NoMatchException("Partial replacement failed: target piece not found: " + pp);
                }
                // replace only the first occurrence
                result = result.replaceFirst(Pattern.quote(pp), Matcher.quoteReplacement(rp));
            } else {
                // target piece empty, but replace piece is not -> just append
                result += rp;
            }
        }
        return result;
    }

    /**
     * Tries perfect replace first, then leading-whitespace-insensitive.
     * Throws AmbiguousMatchException if more than one match is found in either step,
     * or NoMatchException if no matches are found
     */
    public static String perfectOrWhitespace(String[] originalLines,
                                             String[] targetLines,
                                             String[] replaceLines)
    throws AmbiguousMatchException, NoMatchException
    {
        try {
            return perfectReplace(originalLines, targetLines, replaceLines);
        } catch (NoMatchException e) {
            return replaceIgnoringWhitespace(originalLines, targetLines, replaceLines);
        }
    }

    /**
     * Tries exact line-by-line match. Returns the post-replacement lines on success.
     * Throws AmbiguousMatchException if multiple exact matches are found.
     * Throws NoMatchException if no exact match is found.
     */
    public static String perfectReplace(String[] originalLines,
                                        String[] targetLines,
                                        String[] replaceLines)
    throws AmbiguousMatchException, NoMatchException
    {
        // special-case replace entire file (empty target)
        if (targetLines.length == 0) {
            return String.join("", replaceLines);
        }

        List<Integer> matches = new ArrayList<>();

        outer:
        for (int i = 0; i <= originalLines.length - targetLines.length; i++) {
            for (int j = 0; j < targetLines.length; j++) {
                if (!Objects.equals(originalLines[i + j], targetLines[j])) {
                    continue outer;
                }
            }
            matches.add(i);
            if (matches.size() > 1) {
                throw new AmbiguousMatchException("Multiple exact matches found for the oldLines");
            }
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("No exact matches found for the search block");
        }

        // Exactly one match
        int matchStart = matches.getFirst();
        List<String> newLines = new ArrayList<>();
        // everything before
        newLines.addAll(Arrays.asList(originalLines).subList(0, matchStart));
        // add replacement
        newLines.addAll(Arrays.asList(replaceLines));
        // everything after
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + targetLines.length, originalLines.length));

        return String.join("", newLines);
    }

    /**
     * Attempt a line-for-line match ignoring whitespace. If found, replace that
     * slice by adjusting each replacement line's indentation to preserve the relative
     * indentation from the 'search' lines.
     * Throws AmbiguousMatchException if multiple matches are found ignoring whitespace.
     * Throws NoMatchException if no match is found ignoring whitespace, or if the search block contained only whitespace.
     */
    static String replaceIgnoringWhitespace(String[] originalLines,
                                            String[] targetLines,
                                            String[] replaceLines)
    throws AmbiguousMatchException, NoMatchException
    {
        var truncatedTarget = removeLeadingTrailingEmptyLines(targetLines);
        var truncatedReplace = removeLeadingTrailingEmptyLines(replaceLines);

        if (truncatedTarget.length == 0) {
            // Empty target is handled by perfectReplace -- this means we just had whitespace, so fail the edit
            return null;
        }

        List<Integer> matches = new ArrayList<>();
        int needed = truncatedTarget.length;

        for (int start = 0; start <= originalLines.length - needed; start++) {
            if (matchesIgnoringWhitespace(originalLines, start, truncatedTarget)) {
                matches.add(start);
                if (matches.size() > 1) {
                    throw new AmbiguousMatchException("No exact matches found, and multiple matches found ignoring whitespace");
                }
            }
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("No matches found ignoring whitespace");
        }

        // Exactly one match
        int matchStart = matches.getFirst();

        List<String> newLines = new ArrayList<>(Arrays.asList(originalLines).subList(0, matchStart));
        if (truncatedReplace.length > 0) {
            var adjusted = getLeadingWhitespace(originalLines[matchStart])
                    + truncatedReplace[0].trim() + "\n";
            newLines.add(adjusted);
            newLines.addAll(Arrays.asList(truncatedReplace).subList(1, truncatedReplace.length));
        }
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + needed, originalLines.length));
        return String.join("", newLines);
    }

    private static String[] removeLeadingTrailingEmptyLines(String[] targetLines) {
        int pStart = 0;
        while (pStart < targetLines.length && targetLines[pStart].trim().isEmpty()) {
            pStart++;
        }
        // Skip trailing blank lines in the search block
        int pEnd = targetLines.length;
        while (pEnd > pStart && targetLines[pEnd - 1].trim().isEmpty()) {
            pEnd--;
        }
        return Arrays.copyOfRange(targetLines, pStart, pEnd);
    }

    /**
     * return true if the targetLines match the originalLines starting at 'start', ignoring whitespace.
     */
    static boolean matchesIgnoringWhitespace(String[] originalLines, int start, String[] targetLines) {
        if (start + targetLines.length > originalLines.length) {
            return false;
        }
        for (int i = 0; i < targetLines.length; i++) {
            if (!nonWhitespace(originalLines[start + i]).equals(nonWhitespace(targetLines[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the non-whitespace characters in `line`
     */
    private static String nonWhitespace(String line) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * @return the whitespace prefix in this line.
     */
    static String getLeadingWhitespace(String line) {
        assert line.endsWith("\n");
        int count = 0;
        for (int i = 0; i < line.length() - 1; i++) { // -1 because we threw newline onto everything
            if (Character.isWhitespace(line.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return line.substring(0, count);
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to
     * currentFilename if it's not null.
     */
    private static ContentLines prep(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        // ensure it ends with newline
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        // Convert to list of lines, each ending with a newline
        List<String> linesList = content.lines().map(line -> line + "\n").collect(Collectors.toList());
        return new ContentLines(content, linesList);
    }

    private record ContentLines(String original, List<String> lines) { // Ensures this matches the list type
    }

    /**
     * Resolves a filename string to a ProjectFile.
     * Handles partial paths, checks against editable files, tracked files, and project files.
     *
     * @param cm        The context manager.
     * @param filename  The filename string to resolve (potentially partial).
     * @return The resolved ProjectFile.
     * @throws SymbolNotFoundException  if the file cannot be found.
     * @throws SymbolAmbiguousException if the filename matches multiple files.
     */
    static ProjectFile resolveProjectFile(IContextManager cm, @Nullable String filename)
    throws SymbolNotFoundException, SymbolAmbiguousException
    {
        var file = cm.toFile(filename);

        // 1. Exact match (common case)
        if (file.exists()) {
            return file;
        }

        // 2. Check editable files (case-insensitive basename match)
        var editableMatches = cm.getEditableFiles().stream()
                .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                .toList();
        if (editableMatches.size() == 1) {
            logger.debug("Resolved partial filename [{}] to editable file [{}]", filename, editableMatches.getFirst());
            return editableMatches.getFirst();
        }
        if (editableMatches.size() > 1) {
            throw new SymbolAmbiguousException("Filename '%s' matches multiple editable files: %s".formatted(filename, editableMatches));
        }

        // 3. Check tracked files in git repo (substring match)
        var repo = cm.getRepo();
        if (repo != null) {
            var trackedMatches = repo.getTrackedFiles().stream()
                    .filter(f -> f.toString().contains(filename))
                    .toList();
            if (trackedMatches.size() == 1) {
                logger.debug("Resolved partial filename [{}] to tracked file [{}]", filename, trackedMatches.getFirst());
                return trackedMatches.getFirst();
            }
            if (trackedMatches.size() > 1) {
                // Prefer exact basename match if available among tracked files
                var exactBaseMatches = trackedMatches.stream()
                        .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                        .toList();
                if (exactBaseMatches.size() == 1) {
                    logger.debug("Resolved ambiguous tracked filename [{}] to exact basename match [{}]", filename, exactBaseMatches.getFirst());
                    return exactBaseMatches.getFirst();
                }
                throw new SymbolAmbiguousException("Filename '%s' matches multiple tracked files: %s".formatted(filename, trackedMatches));
            }
        }

        // 4. Not found anywhere
        throw new SymbolNotFoundException("Filename '%s' could not be resolved to an existing file.".formatted(filename));
    }
}