package ai.brokk.prompts;

import ai.brokk.analyzer.ProjectFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/** Shared utilities for parsing edit blocks. Used by both the prompt parser and the flexmark markdown parser. */
public final class EditBlockUtils {

    // Pattern for the "<<<<<<< SEARCH [filename]" line (filename optional)
    public static final Pattern HEAD =
            Pattern.compile("^ {0,3}<{5,9}\\s+SEARCH(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for the "=======" divider line
    public static final Pattern DIVIDER = Pattern.compile("^ {0,3}={5,9}(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for the ">>>>>>> REPLACE [filename]" line (filename optional)
    public static final Pattern UPDATED =
            Pattern.compile("^ {0,3}>{5,9}\\s+REPLACE(?:\\s+(\\S.*))?\\s*$", Pattern.MULTILINE);

    // Pattern for opening code fence (captures optional language or filename token)
    public static final Pattern OPENING_FENCE = Pattern.compile("^ {0,3}```(?:\\s*(\\S[^`\\s]*))?\\s*$");

    // Default fence markers
    public static final List<String> DEFAULT_FENCE = List.of("```", "```");

    private EditBlockUtils() {}
    /**
     * Determines if a string looks like a path or filename. Simple heuristic: contains a dot or slash character.
     *
     * @param s the string to check
     * @return true if the string appears to be a path
     */
    public static boolean looksLikePath(String s) {
        return s.contains(".") || s.contains("/");
    }

    /**
     * Removes any extra lines containing the filename or triple-backticks fences. This is used to clean up the contents
     * of search/replace blocks.
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
        if (!fname.isBlank() && lines.length > 0) {
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
     * Returns a cleaned candidate "filename" token from a single line.
     *
     * <p>Rules: - Trims leading/trailing whitespace. - If the line is exactly "...", or exactly "```", returns null. -
     * Removes a trailing colon, a leading '#', a leading '//' (with optional space), and surrounding backticks or
     * asterisks. - Returns null if the result is empty after cleaning.
     *
     * <p>Notes: - For code-fence lines like "```path/to/file.java" or "```java", the leading backticks are stripped and
     * the remainder ("path/to/file.java" or "java") is returned. - The result is not validated as a real path; callers
     * can use looksLikePath to make that determination.
     *
     * @param line line to process
     * @return cleaned token or null if nothing usable could be extracted
     */
    public static @Nullable String stripFilename(String line) {
        String s = line.trim();
        if (s.equals("...") || s.equals(DEFAULT_FENCE.getFirst())) {
            return null;
        }
        // remove trailing colons, leading #, etc.
        s = s.replaceAll(":$", "").replaceFirst("^#", "").trim();
        // strip leading line comment marker
        s = s.replaceFirst("^//\\s*", "");
        // strip surrounding backticks and asterisks
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^\\*+|\\*+$", "");
        // final trim after removals
        s = s.trim();
        return s.isBlank() ? null : s;
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to currentFilename if
     * it's not null.
     *
     * @param lines array of all document lines
     * @param headIndex index where the HEAD marker (<<<<<<< SEARCH) was found
     * @param projectFiles set of valid project files to match against (can be empty)
     * @param currentPath fallback filename if nothing better is found
     * @return best filename guess based on context
     */
    @VisibleForTesting
    @Nullable
    static String findFilenameNearby(
            String[] lines, int headIndex, Set<ProjectFile> projectFiles, @Nullable String currentPath) {
        // Guard against empty arrays
        if (lines.length == 0 || headIndex < 0) {
            return currentPath;
        }

        // Search up to 3 lines above headIndex
        int start = Math.max(0, headIndex - 3);
        var candidates = new ArrayList<String>();
        var rawContextLines = new ArrayList<String>();

        for (int i = headIndex - 1; i >= start; i--) {
            String rawLine = lines[i];
            String s = rawLine.trim();
            String possible = stripFilename(s);
            if (possible != null && !possible.isBlank()) {
                candidates.add(possible);
            }
            rawContextLines.add(rawLine);
            // If not a fence line, stop scanning further upward (keeps prior behavior for fenced blocks)
            if (!s.startsWith("```")) {
                break;
            }
        }

        // (2a) Exact match (including path) in valid ProjectFiles using stripped candidates
        for (var c : candidates) {
            if (projectFiles.stream().anyMatch(f -> f.toString().equals(c))) {
                return c;
            }
        }

        // (2a continued) Candidate-to-ProjectFile filename containment (best-effort)
        var matches = candidates.stream()
                .flatMap(c -> projectFiles.stream().filter(f -> f.getFileName().contains(c)).findFirst().stream())
                .map(ProjectFile::toString)
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }

        // (2b) Look for unique ProjectFile name occurrence in the RAW, unstripped context lines
        var rawMatches = rawContextLines.stream()
                .flatMap(raw -> projectFiles.stream().filter(f -> raw.contains(f.getFileName())))
                .distinct()
                .toList();
        if (rawMatches.size() == 1) {
            return rawMatches.getFirst().toString();
        }

        // If any stripped candidate looks like a path (has an extension), use it verbatim.
        for (var c : candidates) {
            if (c.contains(".")) {
                return c;
            }
        }

        // Fallback to the first raw candidate, else currentPath
        if (!candidates.isEmpty()) return candidates.getFirst();
        return currentPath;
    }
}
