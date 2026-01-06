package ai.brokk.util;

import ai.brokk.ICodeReview;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * Parses out-of-band code excerpts from LLM responses.
 * Expected format:
 * BRK_EXCERPT_$ID
 * $filename
 * ```[lang]
 * $excerptContent
 * ```
 */
@NullMarked
public class ReviewExcerptParser {

    public static final ReviewExcerptParser instance = new ReviewExcerptParser();

    private ReviewExcerptParser() {}

    private enum State {
        SEARCHING,
        EXPECTING_FILENAME,
        EXPECTING_FENCE,
        IN_CONTENT
    }

    /**
     * Parses the provided text for BRK_EXCERPT blocks.
     *
     * @param text The raw response text to parse.
     * @return A map of integer ID to CodeExcerpt.
     */
    public Map<Integer, ICodeReview.CodeExcerpt> parseExcerpts(String text) {
        Map<Integer, ICodeReview.CodeExcerpt> excerpts = new HashMap<>();
        String[] lines = text.split("\\R", -1);

        State state = State.SEARCHING;
        Integer currentId = null;
        String currentFile = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // Check for new BRK_EXCERPT marker in any state except SEARCHING
            // This handles unclosed blocks by abandoning them when a new marker appears
            if (state != State.SEARCHING && trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                try {
                    int newId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                    // Found a new excerpt marker - abandon current block and start fresh
                    currentId = newId;
                    currentFile = null;
                    currentContent.setLength(0);
                    state = State.EXPECTING_FILENAME;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not a valid marker, fall through to normal processing
                }
            }

            switch (state) {
                case SEARCHING -> {
                    if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {
                            // Skip non-numeric IDs
                        }
                    }
                }
                case EXPECTING_FILENAME -> {
                    if (!trimmed.isEmpty()) {
                        currentFile = trimmed;
                        state = State.EXPECTING_FENCE;
                    }
                }
                case EXPECTING_FENCE -> {
                    if (trimmed.startsWith("```")) {
                        state = State.IN_CONTENT;
                        currentContent.setLength(0);
                    } else if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {
                            state = State.SEARCHING;
                        }
                    } else if (!trimmed.isEmpty()) {
                        // Unexpected text between filename and fence, reset
                        state = State.SEARCHING;
                    }
                }
                case IN_CONTENT -> {
                    // Closing fence must start at column 0 (no leading whitespace) and be exactly ```
                    if (line.equals("```") || line.startsWith("```") && line.substring(3).isBlank()) {
                        if (currentId != null && currentFile != null) {
                            excerpts.put(currentId, new ICodeReview.CodeExcerpt(currentFile, currentContent.toString()));
                        }
                        state = State.SEARCHING;
                        currentId = null;
                        currentFile = null;
                        currentContent.setLength(0);
                    } else {
                        if (!currentContent.isEmpty()) {
                            currentContent.append("\n");
                        }
                        currentContent.append(line);
                    }
                }
            }
        }

        return Map.copyOf(excerpts);
    }
}
