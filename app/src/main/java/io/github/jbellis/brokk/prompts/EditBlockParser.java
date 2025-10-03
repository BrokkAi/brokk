package io.github.jbellis.brokk.prompts;

import static io.github.jbellis.brokk.prompts.EditBlockUtils.*;

import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.*;
import org.jetbrains.annotations.Nullable;

/**
 * Parses SEARCH/REPLACE edit blocks.
 *
 * <p>Robustness goals: - Only treat our own markers ("<<<<<<< SEARCH", "=======", ">>>>>>> REPLACE") as block
 * boundaries. Do NOT confuse git conflict markers like "<<<<<<< HEAD" / ">>>>>>> branch" with our markers. - Allow
 * nested conflict markers *inside* the before/after payloads. - Continue to support "fence-less" blocks that start
 * directly with "<<<<<<< SEARCH". - Be forgiving where unambiguous, and surface meaningful errors otherwise.
 *
 * <p>Public API remains unchanged.
 */
public class EditBlockParser {
    public static EditBlockParser instance = new EditBlockParser();

    protected EditBlockParser() {}

    /** Parses the given content into ParseResult (only edit blocks; ignores plain text). */
    public EditBlock.ParseResult parseEditBlocks(String content, Set<ProjectFile> projectFiles) {
        var all = parse(content, projectFiles);
        var editBlocks = all.blocks().stream()
                .map(EditBlock.OutputBlock::block)
                .filter(Objects::nonNull)
                .toList();

        if (!editBlocks.isEmpty() || all.parseError() != null) {
            return new EditBlock.ParseResult(editBlocks, all.parseError());
        }

        // attempt to detect attempted edits that we couldn't parse
        String error = null;
        if (looksLikeUnifiedDiff(content)) {
            error = "It looks like you pasted a unified diff (lines starting with @@ and + / -). "
                    + "Please provide edits using SEARCH/REPLACE edit blocks (<<<<<<< SEARCH / ======= / >>>>>>> REPLACE).";
        } else if (content.contains("<<<<<<< SEARCH") || content.contains(">>>>>>> REPLACE")) {
            error = "It looks like you tried to include an edit block, but I couldn't parse it.";
        }

        return new EditBlock.ParseResult(editBlocks, error);
    }

    /**
     * Heuristic to detect unified diff-like input: - At least one line starting with "@@" - At least one other line
     * starting with "+" or "-" (but not the file header lines "+++ " or "--- ")
     */
    private static boolean looksLikeUnifiedDiff(String content) {
        boolean hasAtAt = false;
        boolean hasPlusMinus = false;
        var lines = content.split("\n", -1);
        for (var raw : lines) {
            var l = raw;
            if (l.startsWith("@@")) {
                hasAtAt = true;
            } else if ((l.startsWith("+") || l.startsWith("-")) && !l.startsWith("+++ ") && !l.startsWith("--- ")) {
                hasPlusMinus = true;
            }
            if (hasAtAt && hasPlusMinus) return true;
        }
        return false;
    }

    /**
     * Parses the given content into a sequence of OutputBlock records (plain text or edit blocks). Malformed blocks
     * report a parseError and stop parsing at the first error (unchanged behavior), but previously parsed content is
     * preserved in the returned blocks list.
     */
    public EditBlock.ExtendedParseResult parse(String content, Set<ProjectFile> projectFiles) {
        var blocks = new ArrayList<EditBlock.OutputBlock>();

        var lines = content.split("\n", -1);
        var plain = new StringBuilder();

        int i = 0;
        @Nullable String currentFilename = null; // remembered for fence-less blocks

        while (i < lines.length) {
            var trimmed = lines[i].trim();

            // 1) Block variant with code fence:
            //     ```
            //     [optional <filename>]
            //     <<<<<<< SEARCH
            //        ...
            //     =======
            //        ...
            //     >>>>>>> REPLACE
            //     ```
            if (isFence(trimmed)) {
                var searchAtNext = (i + 1 < lines.length) && isSearch(lines[i + 1]);
                var searchAtNextNext = (i + 2 < lines.length) && isSearch(lines[i + 2]);

                if (searchAtNext || searchAtNextNext) {
                    // Flush preceding plain text
                    flushPlain(blocks, plain);

                    // Determine block-specific filename and where SEARCH is
                    @Nullable String blockFilename;
                    int searchIndex;

                    if (searchAtNext) {
                        // No explicit filename line
                        blockFilename = findFilenameNearby(lines, i + 1, projectFiles, currentFilename);
                        searchIndex = i + 1;
                    } else {
                        // There is a middle line; treat it as a filename if we can parse it.
                        var filenameLine = lines[i + 1];
                        var candidatePath = stripFilename(filenameLine);
                        blockFilename = (candidatePath != null && !candidatePath.isBlank())
                                ? candidatePath
                                : findFilenameNearby(lines, i + 2, projectFiles, currentFilename);
                        searchIndex = i + 2;
                    }

                    // Scan body starting at the line AFTER the "<<<<<<< SEARCH" line
                    var scan = scanSearchReplaceBody(lines, searchIndex + 1);
                    if (scan.errorMessage != null) {
                        return new EditBlock.ExtendedParseResult(blocks, scan.errorMessage);
                    }
                    i = scan.nextIndex; // positioned after ">>>>>>> REPLACE"

                    var beforeJoined =
                            stripQuotedWrapping(String.join("\n", scan.before), Objects.toString(blockFilename, ""));
                    var afterJoined =
                            stripQuotedWrapping(String.join("\n", scan.after), Objects.toString(blockFilename, ""));

                    if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                    if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) afterJoined += "\n";

                    blocks.add(EditBlock.OutputBlock.edit(
                            new EditBlock.SearchReplaceBlock(blockFilename, beforeJoined, afterJoined)));

                    // Persist the discovered filename for subsequent fence-less blocks
                    if (blockFilename != null && !blockFilename.isBlank()) {
                        currentFilename = blockFilename;
                    }

                    // optional closing fence
                    if (i < lines.length && isFence(lines[i].trim())) {
                        i++;
                    }
                    continue;
                }
                // If it's a fence but not our edit block, fall through to plain accumulation below.
            }

            // 2) Fence-less variant starting directly with "<<<<<<< SEARCH"
            if (isSearch(trimmed)) {
                flushPlain(blocks, plain);

                currentFilename = findFilenameNearby(lines, i, projectFiles, currentFilename);

                // first line after "<<<<<<< SEARCH"
                i++;

                var scan = scanSearchReplaceBody(lines, i);
                if (scan.errorMessage != null) {
                    return new EditBlock.ExtendedParseResult(blocks, scan.errorMessage);
                }
                i = scan.nextIndex; // after ">>>>>>> REPLACE"

                var beforeJoined =
                        stripQuotedWrapping(String.join("\n", scan.before), Objects.toString(currentFilename, ""));
                var afterJoined =
                        stripQuotedWrapping(String.join("\n", scan.after), Objects.toString(currentFilename, ""));

                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) beforeJoined += "\n";
                if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) afterJoined += "\n";

                blocks.add(EditBlock.OutputBlock.edit(
                        new EditBlock.SearchReplaceBlock(currentFilename, beforeJoined, afterJoined)));

                // optional fence directly after a fence-less block (some LLMs add it)
                if (i < lines.length && isFence(lines[i].trim())) {
                    i++;
                }
                continue;
            }

            // 3) Not part of an edit block — accumulate plain text
            plain.append(lines[i]);
            if (i < lines.length - 1) {
                plain.append("\n");
            }
            i++;
        }

        // Flush any trailing plain text
        flushPlain(blocks, plain);

        return new EditBlock.ExtendedParseResult(blocks, null);
    }

    /* ============================== helpers ============================== */

    private static boolean isFence(String trimmed) {
        // Only treat a bare triple-backtick line as a fence (consistent with previous behavior)
        return trimmed.equals(DEFAULT_FENCE.get(0));
    }

    private static boolean isSearch(String line) {
        return line.trim().equalsIgnoreCase("<<<<<<< SEARCH");
    }

    private static boolean isReplace(String line) {
        return line.trim().equalsIgnoreCase(">>>>>>> REPLACE");
    }

    private static boolean isDivider(String line) {
        return line.trim().equals("=======");
    }

    private static boolean looksLikeAnyConflictStart(String line) {
        var t = line.trim();
        return t.startsWith("<<<<<<< ");
    }

    private static boolean looksLikeAnyConflictEnd(String line) {
        var t = line.trim();
        return t.startsWith(">>>>>>> ");
    }

    private static void flushPlain(List<EditBlock.OutputBlock> blocks, StringBuilder plain) {
        if (!plain.toString().isBlank()) {
            blocks.add(EditBlock.OutputBlock.plain(plain.toString()));
            plain.setLength(0);
        }
    }

    /**
     * Scan lines starting immediately after "<<<<<<< SEARCH" until we find the matching top-level ">>>>>>> REPLACE".
     * While scanning, we treat "=======" as the divider between before/after only when we are at top-level (depth ==
     * 0).
     *
     * <p>Nesting rules:
     *
     * <ul>
     *   <li>Any subsequent "<<<<<<< SEARCH" inside the payload increases depth (nested edit block literal).
     *   <li>Any ">>>>>>> REPLACE" decreases depth; it only terminates the scan when depth == 0 (the top-level end).
     *   <li>Generic git-conflict markers (e.g., "<<<<<<< HEAD" / ">>>>>>> branch") also adjust depth.
     * </ul>
     *
     * <p>Nested markers therefore do not interfere with our parsing and are preserved verbatim in the payload.
     */
    private static ScanResult scanSearchReplaceBody(String[] lines, int startIndex) {
        var before = new ArrayList<String>();
        var after = new ArrayList<String>();
        boolean inAfter = false;
        int depth = 0; // depth of nested markers (either our own SEARCH/REPLACE or generic git conflict)
        int i = startIndex;

        while (i < lines.length) {
            var raw = lines[i];
            var trimmed = raw.trim();

            // Top-level end marker closes the block only when not nested
            if (depth == 0 && isReplace(trimmed)) {
                return new ScanResult(i + 1, before, after, ensureHasTopLevelDivider(inAfter));
            }

            // Top-level divider splits before/after *only once*
            if (depth == 0 && isDivider(trimmed) && !inAfter) {
                inAfter = true;
                i++;
                continue;
            }

            // Maintain nesting for our own markers when they appear inside the payload
            if (isSearch(trimmed)) {
                depth++; // nested edit block (literal) begins
            } else if (isReplace(trimmed)) {
                if (depth > 0) {
                    depth--; // nested edit block (literal) ends
                }
            } else {
                // Maintain nesting for generic git conflict markers that are not our own
                if (looksLikeAnyConflictStart(raw)) {
                    depth++;
                } else if (looksLikeAnyConflictEnd(raw)) {
                    if (depth > 0) depth--;
                }
            }

            if (!inAfter) before.add(raw);
            else after.add(raw);
            i++;
        }

        // If we exit the loop, we never saw the matching top-level ">>>>>>> REPLACE"
        return new ScanResult(i, before, after, "Expected >>>>>>> REPLACE");
    }

    private static @Nullable String ensureHasTopLevelDivider(boolean sawTopLevelDivider) {
        return sawTopLevelDivider ? null : "Expected ======= divider after <<<<<<< SEARCH";
    }

    /** Internal scan result. */
    private static final class ScanResult {
        final int nextIndex; // index to continue from (line after >>>>>>> REPLACE)
        final List<String> before;
        final List<String> after;
        final @Nullable String errorMessage; // null if ok

        ScanResult(int nextIndex, List<String> before, List<String> after, @Nullable String errorMessage) {
            this.nextIndex = nextIndex;
            this.before = before;
            this.after = after;
            this.errorMessage = errorMessage;
        }
    }
}
