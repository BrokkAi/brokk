package io.github.jbellis.brokk.gui.mop.stream.flex;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared utilities for parsing edit blocks.
 * Used by both the prompt parser and the flexmark markdown parser.
 */
public final class EditBlockUtils {

    // Pattern for the "<<<<<<< SEARCH [filename]" line (filename optional)
    public static final Pattern HEAD =
            Pattern.compile("^ {0,3}<{5,9}\\s+SEARCH(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for the "=======" divider line
    public static final Pattern DIVIDER =
            Pattern.compile("^ {0,3}={5,9}(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for the ">>>>>>> REPLACE [filename]" line (filename optional)
    public static final Pattern UPDATED =
            Pattern.compile("^ {0,3}>{5,9}\\s+REPLACE(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for opening code fence (captures optional language or filename token)
    public static final Pattern OPENING_FENCE =
            Pattern.compile("^ {0,3}```(?:\\s*(\\S[^`\\s]*))?\\s*$");

    // Default fence markers
    public static final List<String> DEFAULT_FENCE = List.of("```", "```");

    private EditBlockUtils() {}
    /**
     * Determines if a string looks like a path or filename.
     * Simple heuristic: contains a dot or slash character.
     *
     * @param s the string to check
     * @return true if the string appears to be a path
     */
    public static boolean looksLikePath(String s) {
        return s.contains(".") || s.contains("/");
    }

    /**
     * Removes any extra lines containing the filename or triple-backticks fences.
     * This is used to clean up the contents of search/replace blocks.
     *
     * @param block the text content to clean
     * @param fname the filename associated with this block
     * @return cleaned text with fences and filename lines removed
     */
    public static String stripQuotedWrapping(String block, String fname) {
        if (block.isEmpty()) {
            return block;
        }
        String[] lines = block.split("\n", -1);

        // If first line ends with the filename's filename
        if (fname != null && lines.length > 0) {
            String fn = new File(fname).getName();
            if (lines[0].trim().endsWith(fn)) {
                lines = Arrays.copyOfRange(lines, 1, lines.length);
            }
        }
        // If triple-backtick block
        if (lines.length >= 2
                && lines[0].startsWith(DEFAULT_FENCE.getFirst())
                && lines[lines.length - 1].startsWith(DEFAULT_FENCE.getLast())) {
            lines = Arrays.copyOfRange(lines, 1, lines.length - 1);
        }
        String result = String.join("\n", lines);
        if (!result.isEmpty() && !result.endsWith("\n")) {
            result += "\n";
        }
        return result;
    }

    /**
     * Extracts a filename from a line, cleaning up common markers and decorations.
     * Ignores lines that are just ``` or ...
     *
     * @param line the line to process
     * @return extracted filename or null if none found
     */
    public static @Nullable String stripFilename(String line) {
        String s = line.trim();
        if (s.equals("...") || s.equals(DEFAULT_FENCE.getFirst())) {
            return null;
        }
        // remove trailing colons, leading #, etc.
        s = s.replaceAll(":$", "").replaceFirst("^#", "").trim();
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^\\*+|\\*+$", "");
        return s.isBlank() ? null : s;
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to
     * currentFilename if it's not null.
     *
     * @param lines array of all document lines
     * @param headIndex index where the HEAD marker (<<<<<<< SEARCH) was found
     * @param projectFiles set of valid project files to match against (can be empty)
     * @param currentPath fallback filename if nothing better is found
     * @return best filename guess based on context
     */
    @Nullable
    public static String findFileNameNearby(String[] lines,
                                            int headIndex,
                                            Set<ProjectFile> projectFiles,
                                            @Nullable String currentPath)
    {
        // Guard against empty arrays
        if (lines.length == 0 || headIndex < 0) {
            return currentPath;
        }

        // Search up to 3 lines above headIndex
        int start = Math.max(0, headIndex - 3);
        var candidates = new ArrayList<String>();
        for (int i = headIndex - 1; i >= start; i--) {
            String s = lines[i].trim();
            String possible = stripFilename(s);
            if (possible != null && !possible.isBlank()) {
                candidates.add(possible);
            }
            // If not a fence line, break.
            if (!s.startsWith("```")) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            return currentPath;
        }

        // 1) Exact match (including path) in validFilenames
        for (var c : candidates) {
            if (projectFiles.stream().anyMatch(f -> f.toString().equals(c))) {
                return c;
            }
        }

        // 2) Look for a matching filename
        var matches = candidates.stream()
                .flatMap(c -> projectFiles.stream()
                        .filter(f -> f.getFileName().contains(c))
                        .findFirst()
                        .stream())
                .map(ProjectFile::toString)
                .toList();
        if (!matches.isEmpty()) {
            // TODO signal ambiguity?
            return matches.getFirst();
        }

        // 3) If the candidate has an extension and no better match found, just return that.
        for (var c : candidates) {
            if (c.contains(".")) {
                return c;
            }
        }

        // 4) Fallback to the first raw candidate
        if (candidates.isEmpty()) return currentPath; // Redundant check but safe
        return candidates.getFirst();
    }
}
