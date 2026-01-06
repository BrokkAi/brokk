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
public class ReviewParser {

    public static final ReviewParser instance = new ReviewParser();

    private ReviewParser() {}

    private enum State {
        SEARCHING,
        EXPECTING_FILENAME,
        EXPECTING_FENCE,
        IN_CONTENT
    }

    private record FileLine(String path, int line) {}

    private Map<Integer, FileLine> parseExcerptFileLines(String text) {
        Map<Integer, FileLine> files = new HashMap<>();
        String[] lines = text.split("\\R", -1);
        State state = State.SEARCHING;
        Integer currentId = null;
        Pattern fileLinePattern = Pattern.compile("^(.*)\\s+@(\\d+)$");

        for (String line : lines) {
            String trimmed = line.trim();
            if (state != State.SEARCHING && trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                try {
                    currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                    state = State.EXPECTING_FILENAME;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            switch (state) {
                case SEARCHING -> {
                    if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case EXPECTING_FILENAME -> {
                    if (!trimmed.isEmpty()) {
                        Matcher m = fileLinePattern.matcher(trimmed);
                        if (m.matches()) {
                            files.put(currentId, new FileLine(m.group(1).trim(), Integer.parseInt(m.group(2))));
                            state = State.EXPECTING_FENCE;
                        } else {
                            state = State.SEARCHING;
                        }
                    }
                }
                case EXPECTING_FENCE -> {
                    if (trimmed.startsWith("```")) {
                        state = State.IN_CONTENT;
                    }
                }
                case IN_CONTENT -> {
                    if (line.equals("```") || line.startsWith("```") && line.substring(3).isBlank()) {
                        state = State.SEARCHING;
                    }
                }
            }
        }
        return Map.copyOf(files);
    }

    public Map<Integer, ICodeReview.CodeExcerpt> parseExcerpts(String text) {
        Map<Integer, FileLine> files = parseExcerptFileLines(text);
        Map<Integer, String> contents = parseExcerptContents(text);
        Map<Integer, ICodeReview.CodeExcerpt> result = new HashMap<>();
        for (Integer id : files.keySet()) {
            if (contents.containsKey(id)) {
                FileLine fl = files.get(id);
                result.put(id, new ICodeReview.CodeExcerpt(fl.path(), fl.line(), ICodeReview.DiffSide.NEW, contents.get(id)));
            }
        }
        return Map.copyOf(result);
    }

    public Map<Integer, String> parseExcerptFiles(String text) {
        Map<Integer, String> files = new HashMap<>();
        parseExcerptFileLines(text).forEach((id, fl) -> files.put(id, fl.path()));
        return Map.copyOf(files);
    }

    public Map<Integer, String> parseExcerptContents(String text) {
        Map<Integer, String> contents = new HashMap<>();
        String[] lines = text.split("\\R", -1);
        State state = State.SEARCHING;
        Integer currentId = null;
        StringBuilder currentContent = new StringBuilder();
        Pattern fileLinePattern = Pattern.compile("^(.*)\\s+@(\\d+)$");

        for (String line : lines) {
            String trimmed = line.trim();
            if (state != State.SEARCHING && trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                try {
                    currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                    state = State.EXPECTING_FILENAME;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            switch (state) {
                case SEARCHING -> {
                    if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case EXPECTING_FILENAME -> {
                    if (!trimmed.isEmpty()) {
                        Matcher m = fileLinePattern.matcher(trimmed);
                        if (m.matches()) {
                            state = State.EXPECTING_FENCE;
                        } else {
                            state = State.SEARCHING;
                        }
                    }
                }
                case EXPECTING_FENCE -> {
                    if (trimmed.startsWith("```")) {
                        state = State.IN_CONTENT;
                        currentContent.setLength(0);
                    }
                }
                case IN_CONTENT -> {
                    if (line.equals("```") || line.startsWith("```") && line.substring(3).isBlank()) {
                        if (currentId != null) contents.put(currentId, currentContent.toString());
                        state = State.SEARCHING;
                    } else {
                        if (!currentContent.isEmpty()) currentContent.append("\n");
                        currentContent.append(line);
                    }
                }
            }
        }
        return Map.copyOf(contents);
    }
}
