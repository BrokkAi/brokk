package ai.brokk;

import static ai.brokk.prompts.EditBlockUtils.DEFAULT_FENCE;

import ai.brokk.analyzer.*;
import ai.brokk.context.Context;
import ai.brokk.util.IndentUtil;
import com.google.common.base.Splitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/** Utility for extracting and applying before/after search-replace blocks in content. */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

    private EditBlock() {
        // utility class
    }

    /**
     * Helper that returns the first code block found between triple backticks. Skips any text on the same line as the
     * opening backticks (like language specifiers) and starts capturing from the next line. Returns an empty string if
     * no valid block is found.
     */
    public static String extractCodeFromTripleBackticks(String text) {
        // Pattern: ``` followed by optional non-newline chars, then newline, then capture until ```
        // The (.*) is greedy to ensure embedded ``` within the block are treated as content.
        var matcher = Pattern.compile(
                        "```[^\\n]*\\n(.*)```", // Skips language specifier line; (.*) captures content greedily.
                        Pattern.DOTALL)
                .matcher(text);

        if (matcher.find()) {
            // group(1) captures the content between the initial newline (after ```[lang]) and the closing ```
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

        public FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) {
            this(block, reason, "");
        }
    }

    // -- Exceptions for file resolution --

    /** Any symbol resolution related failure caused by the filename given by an LLM. */
    public static sealed class SymbolResolutionException extends Exception {
        public SymbolResolutionException(String message) {
            super(message);
        }
    }

    /** Thrown when a filename provided by the LLM cannot be uniquely resolved. */
    public static final class SymbolNotFoundException extends SymbolResolutionException {
        public SymbolNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when a filename provided by the LLM is not a valid file name, thus cannot be resolved. */
    public static final class SymbolInvalidException extends SymbolResolutionException {
        public SymbolInvalidException(String message) {
            super(message);
        }
    }

    /** Thrown when a filename provided by the LLM matches multiple files. */
    public static final class SymbolAmbiguousException extends SymbolResolutionException {
        public SymbolAmbiguousException(String message) {
            super(message);
        }
    }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks and apply them.
     *
     * <p>Note: it is the responsibility of the caller (e.g. CodeAgent::preCreateNewFiles) to create empty files for
     * blocks corresponding to new files.
     */
    @Blocking
    public static EditResult apply(Context ctx, IConsoleIO io, Collection<SearchReplaceBlock> blocks)
            throws IOException, InterruptedException {
        IContextManager contextManager = ctx.getContextManager();
        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        Map<SearchReplaceBlock, ProjectFile> succeeded = new HashMap<>();
        // Track original file contents before any changes
        Map<ProjectFile, String> originalContentsThisBatch = new HashMap<>();

        // First pass: resolve files and pre-resolve BRK markers BEFORE any file modifications
        record ApplyPlan(ProjectFile file, SearchReplaceBlock block, String effectiveBefore) {}
        List<ApplyPlan> plans = new ArrayList<>();

        for (var block : blocks) {
            final var rawFileName = block.rawFileName();
            ProjectFile file;
            try {
                file = resolveProjectFile(ctx, rawFileName);
            } catch (SymbolAmbiguousException | SymbolInvalidException e) {
                logger.debug("File resolution failed for block [{}]: {}", rawFileName, e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.FILE_NOT_FOUND));
                continue;
            } catch (SymbolNotFoundException e) {
                if (rawFileName == null) {
                    // would have thrown SymbolInvalidException if null
                    failed.add(new FailedBlock(block, EditBlockFailureReason.FILE_NOT_FOUND));
                    continue;
                }
                // create new file for the edit block to work on
                file = contextManager.toFile(rawFileName);
                try {
                    file.write(""); // Using ProjectFile.write handles directory creation internally
                    logger.debug("Pre-created empty file: {}", file);
                } catch (IOException ioException) {
                    io.toolError("Failed to create empty file " + file + ": " + e.getMessage(), "Error");
                }
            }

            // Pre-resolve BRK_CLASS/BRK_FUNCTION so analyzer offsets are from the original file content
            String effectiveBefore = block.beforeText();
            var analyzer = ctx.getContextManager().getAnalyzer();
            try {
                var maybeResolved = resolveBrkSnippet(effectiveBefore, file, analyzer);
                if (maybeResolved != null) {
                    effectiveBefore = maybeResolved;
                    logger.debug("Pre-resolved BRK target snippet for {}:\n{}", rawFileName, effectiveBefore);
                }
            } catch (NoMatchException | AmbiguousMatchException ex) {
                // Record the failure early since we cannot resolve the target entity
                var reason = (ex instanceof NoMatchException)
                        ? EditBlockFailureReason.NO_MATCH
                        : EditBlockFailureReason.AMBIGUOUS_MATCH;

                // Report NoMatch resolution failures to telemetry with useful context, mirroring CodeAgent prelint
                // style.
                if (ex instanceof NoMatchException) {
                    var marker = effectiveBefore.strip();
                    var message = "Failed to resolve BRK snippet in edit block for "
                            + rawFileName + ": "
                            + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    contextManager.reportException(
                            new BrkSnippetNoMatchException(message, ex),
                            Map.of(
                                    "sourcefile", file.getFileName(),
                                    "marker", marker,
                                    "sourceContents", file.read().orElse(""),
                                    "sourceDeclarations",
                                            analyzer.getDeclarations(file).stream()
                                                    .map(CodeUnit::shortName)
                                                    .sorted()
                                                    .collect(Collectors.joining(", "))));
                }

                failed.add(new FailedBlock(block, reason, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                continue;
            }

            plans.add(new ApplyPlan(file, block, effectiveBefore));
        }

        // Second pass: apply in the original order using the pre-resolved search text
        for (var plan : plans) {
            var file = plan.file();
            var block = plan.block();
            var effectiveBefore = plan.effectiveBefore();

            try {
                if (!originalContentsThisBatch.containsKey(file)) {
                    originalContentsThisBatch.put(
                            file, file.exists() ? file.read().orElse("") : "");
                }

                replaceInFile(file, effectiveBefore, block.afterText(), ctx);
                succeeded.put(block, file);
            } catch (NoMatchException | AmbiguousMatchException e) {
                assert originalContentsThisBatch.containsKey(file);
                // check to see if the new contents are already in the file
                // by calling replaceMostSimilarChunk without saving the result
                var originalContent = originalContentsThisBatch.get(file);
                String commentary;
                try {
                    replaceMostSimilarChunk(originalContent, block.afterText(), "");
                    commentary =
                            """
                    The replacement text is already present in the file. If we no longer need to apply
                    this block, omit it from your reply.
                    """;
                } catch (NoMatchException | AmbiguousMatchException e2) {
                    commentary = "";
                }

                if (block.beforeText().lines().anyMatch(line -> line.startsWith("-") || line.startsWith("+"))) {
                    commentary +=
                            """
                              Reminder: Brokk uses SEARCH/REPLACE blocks, not unified diff format.
                              Ensure the `<<<<<<< SEARCH $filename` block matches the existing code exactly.
                              """;
                }

                logger.debug(
                        "Edit application failed for file [{}] {}: {}",
                        file,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                var reason = e instanceof NoMatchException
                        ? EditBlockFailureReason.NO_MATCH
                        : EditBlockFailureReason.AMBIGUOUS_MATCH;
                failed.add(new FailedBlock(block, reason, commentary));
            } catch (IOException e) {
                var msg = "Error applying edit to " + file;
                logger.error("{}: {}", msg, e.getMessage());
                throw new IOException(msg, e);
            } catch (GitAPIException e) {
                var msg = "Non-fatal error: unable to update `%s` in Git".formatted(file);
                logger.error("{}: {}", msg, e.getMessage());
                io.showNotification(IConsoleIO.NotificationRole.INFO, msg);
            }
        }

        originalContentsThisBatch.keySet().retainAll(succeeded.values());
        return new EditResult(originalContentsThisBatch, failed);
    }

    @TestOnly
    public static EditResult apply(IContextManager contextManager, IConsoleIO io, Collection<SearchReplaceBlock> blocks)
            throws IOException, InterruptedException {
        return apply(contextManager.liveContext(), io, blocks);
    }

    /**
     * Simple record storing the parts of a search-replace block. If {@code rawFileName} is non-null, then this block
     * corresponds to a rawFileName’s search/replace. Note, {@code rawFileName} has not been checked for validity.
     */
    public record SearchReplaceBlock(@Nullable String rawFileName, String beforeText, String afterText) {
        public String repr() {
            var beforeText = beforeText();
            var afterText = afterText();
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
                            rawFileName(),
                            beforeText,
                            beforeText.endsWith("\n") ? "" : "\n",
                            afterText,
                            afterText.endsWith("\n") ? "" : "\n",
                            DEFAULT_FENCE.get(1));
        }
    }

    public record ParseResult(SequencedSet<SearchReplaceBlock> blocks, @Nullable String parseError) {}

    public record ExtendedParseResult(List<OutputBlock> blocks, @Nullable String parseError) {}

    /** Represents a segment of the LLM output, categorized as either plain text or a parsed Edit Block. */
    public record OutputBlock(@Nullable String text, @Nullable SearchReplaceBlock block) {
        /** Ensures that exactly one of the fields is non-null. */
        public OutputBlock {
            assert (text == null) != (block == null);
        }

        /** Convenience constructor for plain text blocks. */
        public static OutputBlock plain(String text) {
            return new OutputBlock(text, null);
        }

        /** Convenience constructor for Edit blocks. */
        public static OutputBlock edit(SearchReplaceBlock block) {
            return new OutputBlock(null, block);
        }
    }

    /**
     * Determines if an edit operation constitutes a logical deletion of file content. A deletion occurs if the original
     * content was non-blank and the updated content becomes blank.
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
     * Replace the first occurrence of `beforeText` lines with `afterText` lines in the given file. If the operation
     * results in the file becoming blank (and it wasn't already), the file is deleted and the deletion is staged in Git
     * via the contextManager. Throws NoMatchException if `beforeText` is not found in the file content. Throws
     * AmbiguousMatchException if more than one match is found.
     */
    public static void replaceInFile(ProjectFile file, String beforeText, String afterText, Context ctx)
            throws IOException, NoMatchException, AmbiguousMatchException, GitAPIException, InterruptedException {
        IContextManager contextManager = ctx.getContextManager();
        String original = file.exists() ? file.read().orElse("") : "";
        String updated = replaceMostSimilarChunk(original, beforeText, afterText);

        if (isDeletion(original, updated)) {
            logger.info("Detected deletion for file {}", file);
            Files.deleteIfExists(file.absPath()); // remove from disk
            contextManager.getRepo().remove(file); // stage deletion
            return; // Do not write the blank content
        }

        file.write(updated);
    }

    /** Custom exception thrown when no matching location is found in the file. */
    public static class NoMatchException extends Exception {
        public NoMatchException(String msg) {
            super(msg);
        }
    }

    /** Thrown when more than one matching location is found. */
    public static class AmbiguousMatchException extends Exception {
        public AmbiguousMatchException(String message) {
            super(message);
        }
    }

    /** Exception type used to report BRK snippet NoMatch telemetry with context. */
    static class BrkSnippetNoMatchException extends RuntimeException {
        BrkSnippetNoMatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Attempts perfect/whitespace replacements, then tries "...", then fuzzy. Returns the post-replacement content.
     * Also supports special *marker* search targets: - BRK_CONFLICT_$n (new single-line syntax; replaces
     * BEGIN/END-delimited region with index $n) - BRK_CONFLICT_BEGIN_<n>...BRK_CONFLICT_END_<n> (back-compat: old
     * behavior where SEARCH contained the whole region) - BRK_CLASS <fqcn> (existing) - BRK_FUNCTION <fqMethodName>
     * (existing; rejects overloads as ambiguous)
     *
     * <p>For BRK_CLASS/BRK_FUNCTION, we fetch the exact source via SourceCodeProvider and then proceed as a normal line
     * edit using that snippet as the search block.
     */
    static String replaceMostSimilarChunk(String content, String target, String replace)
            throws AmbiguousMatchException, NoMatchException {
        // -----------------------------
        // 0) BRK_CONFLICT block special-cases
        // -----------------------------
        var trimmedTarget = target.strip();

        // 0a) NEW single-line syntax: "BRK_CONFLICT_$n"
        var conflictLineMatcher = Pattern.compile("^BRK_CONFLICT_(\\d+)\\s*$").matcher(trimmedTarget);
        if (conflictLineMatcher.matches()) {
            var num = conflictLineMatcher.group(1);
            var regionPattern = Pattern.compile("BRK_CONFLICT_BEGIN_" + Pattern.quote(num)
                    + "[\\s\\S]*?BRK_CONFLICT_END_" + Pattern.quote(num)
                    + "(?:\\r?\\n)?");
            var scan = regionPattern.matcher(content);

            int count = 0;
            while (scan.find()) {
                count++;
            }
            if (count == 0) {
                throw new NoMatchException("No matching conflict block found for BRK_CONFLICT_" + num);
            }
            if (count > 1) {
                throw new AmbiguousMatchException("Multiple matching conflict blocks found for BRK_CONFLICT_" + num);
            }
            // Replace the (only) conflict region
            return regionPattern.matcher(content).replaceFirst(Matcher.quoteReplacement(replace));
        }

        // -----------------------------
        // 1) BRK_ENTIRE_FILE (explicit full-file replacement)
        // -----------------------------
        if (Pattern.compile("^BRK_ENTIRE_FILE\\s*$").matcher(trimmedTarget).matches()) {
            // Replace the entire file content with 'replace' (or create new file with that content)
            return replace;
        }

        // -------------------------
        // 2) Normal search/replace (existing behavior)
        // -------------------------
        ContentLines originalCL = prep(content);
        ContentLines targetCl = prep(target);
        ContentLines replaceCL = prep(replace);

        if (logger.isTraceEnabled()) {
            logger.trace(
                    "Original content (non-whitespace):\n{}",
                    originalCL.lines().stream().map(EditBlock::nonWhitespace).collect(Collectors.joining("\n")));
            logger.trace(
                    "Target snippet (non-whitespace):\n{}",
                    targetCl.lines().stream().map(EditBlock::nonWhitespace).collect(Collectors.joining("\n")));
        }

        String[] originalLinesArray = originalCL.lines().toArray(String[]::new);
        String[] targetLinesArray = targetCl.lines().toArray(String[]::new);
        String[] replaceLinesArray = replaceCL.lines().toArray(String[]::new);
        String attempt = perfectOrWhitespace(
                originalLinesArray, targetLinesArray, replaceLinesArray, originalCL.originalEndsWithNewline());
        if (attempt != null) {
            return attempt;
        }

        try {
            attempt = tryDotdotdots(content, target, replace, originalCL.originalEndsWithNewline());
            if (attempt != null) {
                return attempt;
            }
        } catch (IllegalArgumentException e) {
            // Do not mask unexpected pairing errors from "..."; log and continue to the final NoMatch.
            logger.debug(
                    "Ignoring malformed '...' placeholder usage while attempting partial replacement: {}",
                    e.getMessage());
        }

        if (targetCl.lines().size() > 2 && targetCl.lines().getFirst().trim().isEmpty()) {
            List<String> splicedTargetList =
                    targetCl.lines().subList(1, targetCl.lines().size());
            List<String> splicedReplaceList =
                    replaceCL.lines().subList(1, replaceCL.lines().size());

            String[] splicedTargetArray = splicedTargetList.toArray(String[]::new);
            String[] splicedReplaceArray = splicedReplaceList.toArray(String[]::new);

            attempt = perfectOrWhitespace(
                    originalLinesArray, splicedTargetArray, splicedReplaceArray, originalCL.originalEndsWithNewline());
            if (attempt != null) {
                return attempt;
            }

            attempt = tryDotdotdots(
                    content,
                    String.join("\n", splicedTargetArray),
                    String.join("\n", splicedReplaceArray),
                    originalCL.originalEndsWithNewline());
            if (attempt != null) {
                return attempt;
            }
        }

        throw new NoMatchException("No matching oldLines found in content");
    }

    /**
     * If the search/replace has lines of "..." as placeholders, do naive partial replacements. The
     * `originalEndsWithNewline` flag indicates if the `whole` string (original content) ended with a newline. The
     * returned string will preserve this trailing newline status.
     */
    public static @Nullable String tryDotdotdots(
            String whole, String target, String replace, boolean originalEndsWithNewline) throws NoMatchException {
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

        // Adjust the final result to match the original trailing newline status
        if (!result.isEmpty() && result.endsWith("\n")) {
            if (!originalEndsWithNewline) {
                // Result has trailing newline, but original didn't; strip it.
                result = result.substring(0, result.length() - 1);
            }
        } else {
            if (originalEndsWithNewline) {
                // Result does not have trailing newline, but original did; add it.
                result += "\n";
            }
        }
        return result;
    }

    /**
     * Tries perfect replace first, then leading-whitespace-insensitive. Throws AmbiguousMatchException if more than one
     * match is found in either step, or NoMatchException if no matches are found
     */
    static @Nullable String perfectOrWhitespace(
            String[] originalLines, String[] targetLines, String[] replaceLines, boolean originalEndsWithNewline)
            throws AmbiguousMatchException, NoMatchException {
        try {
            return perfectReplace(originalLines, targetLines, replaceLines, originalEndsWithNewline);
        } catch (NoMatchException e) {
            return replaceIgnoringWhitespace(originalLines, targetLines, replaceLines, originalEndsWithNewline);
        }
    }

    /**
     * Tries exact line-by-line match. Returns the post-replacement lines on success. Throws AmbiguousMatchException if
     * multiple exact matches are found. Throws NoMatchException if no exact match is found.
     */
    static @Nullable String perfectReplace(
            String[] originalLines, String[] targetLines, String[] replaceLines, boolean originalEndsWithNewline)
            throws AmbiguousMatchException, NoMatchException {
        // Empty SEARCH is no longer a valid “replace entire file” signal.
        // Callers must use BRK_ENTIRE_FILE explicitly.
        if (targetLines.length == 0) {
            throw new NoMatchException("Empty SEARCH is not allowed; use BRK_ENTIRE_FILE for full-file replacement.");
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

        int matchStart = matches.getFirst();
        List<String> newLines = new ArrayList<>();
        newLines.addAll(Arrays.asList(originalLines).subList(0, matchStart));
        newLines.addAll(Arrays.asList(replaceLines));
        newLines.addAll(Arrays.asList(originalLines).subList(matchStart + targetLines.length, originalLines.length));

        // Reconstruct string: join raw lines with \n, then add a final \n if original had one.
        if (newLines.isEmpty()) {
            return originalEndsWithNewline ? "\n" : "";
        }
        String result = String.join("\n", newLines);
        if (originalEndsWithNewline) {
            result += "\n";
        }
        return result;
    }

    /**
     * Attempt a line-for-line match ignoring whitespace. If found, replace that slice by adjusting each replacement
     * line's indentation to preserve the relative indentation from the 'search' lines. Throws AmbiguousMatchException
     * if multiple matches are found ignoring whitespace. Throws NoMatchException if no match is found ignoring
     * whitespace, or if the search block contained only whitespace.
     */
    static @Nullable String replaceIgnoringWhitespace(
            String[] originalLines, String[] targetLines, String[] replaceLines, boolean originalEndsWithNewline)
            throws AmbiguousMatchException, NoMatchException {
        var truncatedTarget = removeLeadingTrailingEmptyLines(targetLines);
        var truncatedReplace = removeLeadingTrailingEmptyLines(replaceLines);

        if (truncatedTarget.length == 0) {
            // If target had only whitespace (or was empty), it's not a valid search.
            if (Arrays.stream(targetLines).allMatch(String::isBlank)) {
                throw new NoMatchException("Search block consists only of whitespace and cannot be matched.");
            }
            return null; // Fall through to NoMatchException from the caller if this specific case wasn't caught.
        }

        List<Integer> matches = new ArrayList<>();
        int needed = truncatedTarget.length;

        for (int start = 0; start <= originalLines.length - needed; start++) {
            if (matchesIgnoringWhitespace(originalLines, start, truncatedTarget)) {
                matches.add(start);
                if (matches.size() > 1) {
                    throw new AmbiguousMatchException(
                            "No exact matches found, and multiple matches found ignoring whitespace");
                }
            }
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("No matches found ignoring whitespace");
        }

        // Exactly one match
        int matchStart = matches.getFirst();

        List<String> resultLines = new ArrayList<>(Arrays.asList(originalLines).subList(0, matchStart));
        if (truncatedReplace.length > 0) {
            String baseIndent = getLeadingWhitespace(originalLines[matchStart]);
            int baseTargetIndent = IndentUtil.countLeadingWhitespace(truncatedTarget[0]);
            int baseReplaceIndent = IndentUtil.countLeadingWhitespace(truncatedReplace[0]);

            // Compute a scaling factor using shared utility to keep logic consistent across prod and tests.
            int targetIndentStep = IndentUtil.findFirstIndentStep(truncatedTarget, baseTargetIndent);
            int replaceIndentStep = IndentUtil.findFirstIndentStep(truncatedReplace, baseReplaceIndent);
            double scale = IndentUtil.computeIndentScale(targetIndentStep, replaceIndentStep);

            for (String replLine : truncatedReplace) {
                if (replLine.trim().isEmpty()) {
                    // Preserve blank line (no trailing spaces)
                    resultLines.add("");
                    continue;
                }
                int replaceRelativeIndent = IndentUtil.countLeadingWhitespace(replLine) - baseReplaceIndent;
                int rawAdjustedRelativeIndent = (int) Math.round(replaceRelativeIndent * scale);
                int adjustedRelativeIndent = Math.max(0, rawAdjustedRelativeIndent);
                if (rawAdjustedRelativeIndent < 0) {
                    logger.warn(
                            "Negative indentation detected in replace block: rawAdjustedRelativeIndent={}, replaceRelativeIndent={}, scale={}",
                            rawAdjustedRelativeIndent,
                            replaceRelativeIndent,
                            scale);
                }
                String adjusted = baseIndent + " ".repeat(adjustedRelativeIndent) + replLine.stripLeading();
                resultLines.add(adjusted);
            }
        }
        resultLines.addAll(Arrays.asList(originalLines).subList(matchStart + needed, originalLines.length));

        // Reconstruct string: join raw lines with \n, then add a final \n if original had one.
        if (resultLines.isEmpty()) {
            return originalEndsWithNewline ? "\n" : "";
        }
        String result = String.join("\n", resultLines);
        if (originalEndsWithNewline) {
            result += "\n";
        }
        return result;
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

    /** return true if the targetLines match the originalLines starting at 'start', ignoring whitespace. */
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

    /** @return the non-whitespace characters in `line` */
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

    /** @return the whitespace prefix in this line. */
    static String getLeadingWhitespace(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return line.substring(0, count);
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to currentFilename if
     * it's not null.
     */
    private static ContentLines prep(String content) {
        boolean originalEndsWithNewline = !content.isEmpty() && content.endsWith("\n");
        List<String> rawLines = content.lines().toList();
        return new ContentLines(content, rawLines, originalEndsWithNewline);
    }

    private record ContentLines(String original, List<String> lines, boolean originalEndsWithNewline) {}

    /**
     * Resolve BRK_CLASS / BRK_FUNCTION markers to source snippets. Returns null if the target is not a BRK marker.
     * Throws on not found or ambiguous cases.
     *
     * @param identifier The identifier string (e.g., "BRK_CLASS com.example.Foo" or "BRK_FUNCTION com.example.Foo.bar")
     * @param target The ProjectFile where the resolution is happening (used to check syntax support)
     * @param analyzer The analyzer to use for resolution
     * @return The resolved source code, or null if not a BRK marker
     */
    private static @Nullable String resolveBrkSnippet(String identifier, ProjectFile target, IAnalyzer analyzer)
            throws NoMatchException, AmbiguousMatchException, InterruptedException {
        identifier = identifier.strip();
        // Resolve BRK_CLASS / BRK_FUNCTION markers to source snippets
        var markerMatcher = Pattern.compile("^BRK_(CLASS|FUNCTION)\\s+(.+)$").matcher(identifier);
        if (!markerMatcher.matches()) {
            return null;
        }

        var kind = markerMatcher.group(1);
        var fqName = markerMatcher.group(2).trim();
        var scpOpt = analyzer.as(SourceCodeProvider.class);
        if (scpOpt.isEmpty()) {
            throw new NoMatchException("Analyzer does not support SourceCodeProvider; cannot use BRK_" + kind);
        }
        var scp = scpOpt.get();

        // Check if the file extension is allowed by configuration and supported by the analyzer
        var fileExt = target.extension().toLowerCase(Locale.ROOT);

        if (!SyntaxAwareConfig.isSyntaxAwareExtension(fileExt)) {
            throw new NoMatchException("BRK markers are disabled for file type '" + target.extension()
                    + "' in this workspace (check BRK_SYNTAX_EXTENSIONS). Use a line-based SEARCH instead.");
        }

        var supportedLanguages = analyzer.languages();
        var isSupportedFile = supportedLanguages.stream()
                .flatMap(lang -> lang.getExtensions().stream())
                .anyMatch(ext -> ext.toLowerCase(Locale.ROOT).equals(fileExt));

        if (!isSupportedFile) {
            throw new NoMatchException("BRK markers are not supported for file type '" + target.extension()
                    + "' in this workspace. Use a line-based SEARCH instead.");
        }

        String shortName = fqName.contains(".") ? fqName.substring(fqName.lastIndexOf('.') + 1) : fqName;
        if ("CLASS".equals(kind)) {
            // Prefer exact definition lookup
            var def = analyzer.getDefinitions(fqName).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (def.isPresent()) {
                var cu = def.get();
                var src = scp.getClassSource(cu, true);
                if (src.isEmpty()) {
                    throw new NoMatchException("No class source found for '" + fqName + "'.");
                }
                return src.get();
            }

            // No exact match; suggest up to 3 similarly named identifiers
            var suggestions = analyzer.searchDefinitions(shortName).stream()
                    .map(CodeUnit::fqName)
                    .filter(n -> {
                        int idx = Math.max(n.lastIndexOf('.'), n.lastIndexOf('$'));
                        return n.substring(idx + 1).equals(shortName);
                    })
                    .limit(3)
                    .toList();
            var extra = suggestions.isEmpty() ? "" : " Did you mean " + String.join(", ", suggestions) + "?";
            throw new NoMatchException("No class source found for '" + fqName + "'." + extra);
        } else {
            Set<String> sources = AnalyzerUtil.getMethodSources(analyzer, fqName, true);
            if (sources.isEmpty()) {
                var suggestions = analyzer.searchDefinitions(shortName).stream()
                        .map(CodeUnit::fqName)
                        .limit(3)
                        .toList();
                var extra = suggestions.isEmpty() ? "" : " Did you mean " + String.join(", ", suggestions) + "?";
                throw new NoMatchException("No method source found for '" + fqName + "'." + extra);
            }
            if (sources.size() > 1) {
                throw new AmbiguousMatchException("Multiple overloads found for '" + fqName + "' (" + sources.size()
                        + "). Please provide a non-overloaded, unique name and re-run.");
            }
            return sources.iterator().next();
        }
    }

    /**
     * Resolves a filename string to a ProjectFile. Handles partial paths, checks against editable files, tracked files,
     * and project files.
     *
     * @param ctx The context.
     * @param filename The filename string to resolve (potentially partial).
     * @return The resolved ProjectFile.
     * @throws SymbolNotFoundException if the file cannot be found.
     * @throws SymbolAmbiguousException if the filename matches multiple files.
     * @throws SymbolInvalidException if the file name is not a valid path (possibly absolute) or is null.
     */
    @Blocking
    static ProjectFile resolveProjectFile(Context ctx, @Nullable String filename)
            throws SymbolNotFoundException, SymbolAmbiguousException, SymbolInvalidException {
        IContextManager cm = ctx.getContextManager();
        if (filename == null || filename.isBlank()) { // Handle null or blank rawFileName early
            throw new SymbolInvalidException("Filename cannot be null or blank.");
        }

        // Strip leading comment prefixes (// or #) that LLMs might include
        var stripped = filename.strip().replaceFirst("^(//|#)\\s*", "");

        ProjectFile file;
        try {
            file = cm.toFile(stripped);
        } catch (IllegalArgumentException e) {
            // This can happen if the LLM provides an absolute path
            throw new SymbolInvalidException(
                    "Filename '%s' is invalid, possibly an absolute path.".formatted(stripped));
        }

        // 1. Exact match (common case)
        if (file.exists()) {
            return file;
        }

        // 1b. Authoritative path rule: if the provided name contains directories, do not fuzzy-match.
        // Treat as "not found" so the caller can create the file explicitly.
        if (stripped.contains(File.separator)) {
            throw new SymbolNotFoundException(
                    "Filename '%s' could not be resolved to an existing file.".formatted(filename));
        }

        // 2. Check editable files (case-insensitive basename match), then narrow by provided path if ambiguous
        var editableMatches = ctx.getAllFragmentsInDisplayOrder().stream()
                .flatMap(f -> f.files().join().stream())
                .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                .toList();
        if (editableMatches.size() == 1) {
            logger.debug("Resolved partial filename [{}] to editable file [{}]", filename, editableMatches.getFirst());
            return editableMatches.getFirst();
        }
        if (editableMatches.size() > 1) {
            // Try to disambiguate using the provided (possibly partial) path substring
            var narrowedEditable = editableMatches.stream()
                    .filter(f -> f.toString().contains(stripped))
                    .toList();
            if (narrowedEditable.size() == 1) {
                logger.debug(
                        "Resolved ambiguous editable filename [{}] to unique match [{}]",
                        filename,
                        narrowedEditable.getFirst());
                return narrowedEditable.getFirst();
            }
            throw new SymbolAmbiguousException("Filename '%s' matches multiple editable files: %s"
                    .formatted(filename, narrowedEditable.isEmpty() ? editableMatches : narrowedEditable));
        }

        // 3. Check tracked files in git repo (substring match)
        var repo = cm.getRepo();
        var trackedMatches = repo.getTrackedFiles().stream()
                .filter(f -> f.toString().contains(stripped))
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
                logger.debug(
                        "Resolved ambiguous tracked filename [{}] to exact basename match [{}]",
                        filename,
                        exactBaseMatches.getFirst());
                return exactBaseMatches.getFirst();
            }
            throw new SymbolAmbiguousException(
                    "Filename '%s' matches multiple tracked files: %s".formatted(filename, trackedMatches));
        }

        // 4. Not found anywhere
        throw new SymbolNotFoundException(
                "Filename '%s' could not be resolved to an existing file.".formatted(filename));
    }
}
