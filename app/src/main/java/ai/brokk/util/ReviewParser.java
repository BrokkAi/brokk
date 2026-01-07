package ai.brokk.util;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
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

    public static WhitespaceMatch findBestMatch(List<WhitespaceMatch> matches, int targetLine) {
        var best = matches.getFirst();
        int minDelta = Math.abs(best.startLine() + 1 - targetLine);
        for (int i = 1; i < matches.size(); i++) {
            int delta = Math.abs(matches.get(i).startLine() + 1 - targetLine);
            if (delta < minDelta) {
                minDelta = delta;
                best = matches.get(i);
            }
        }
        return best;
    }

    private enum State {
        SEARCHING,
        EXPECTING_FILENAME,
        EXPECTING_FENCE,
        IN_CONTENT
    }

    public enum DiffSide {
        OLD,
        NEW
    }

    public String stripExcerpts(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\\R", -1);
        State state = State.SEARCHING;

        for (String line : lines) {
            String trimmed = line.trim();
            switch (state) {
                case SEARCHING -> {
                    if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {
                            sb.append(line).append("\n");
                        }
                    } else {
                        sb.append(line).append("\n");
                    }
                }
                case EXPECTING_FILENAME -> {
                    if (!trimmed.isEmpty()) {
                        state = State.EXPECTING_FENCE;
                    }
                }
                case EXPECTING_FENCE -> {
                    if (trimmed.startsWith("```")) {
                        state = State.IN_CONTENT;
                    }
                }
                case IN_CONTENT -> {
                    if (line.equals("```")
                            || (line.startsWith("```") && line.substring(3).isBlank())) {
                        state = State.SEARCHING;
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    public Map<Integer, RawExcerpt> parseExcerpts(String text) {
        Map<Integer, RawExcerpt> results = new HashMap<>();
        String[] lines = text.split("\\R", -1);
        State state = State.SEARCHING;
        Integer currentId = null;
        String currentFile = null;
        int currentLineNum = -1;
        StringBuilder currentContent = new StringBuilder();
        Pattern fileLinePattern = Pattern.compile("^(.*)\\s+@(\\d+)$");

        for (String line : lines) {
            String trimmed = line.trim();
            if (state != State.SEARCHING && trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                try {
                    currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                    state = State.EXPECTING_FILENAME;
                    continue;
                } catch (NumberFormatException ignored) {
                    // expected when ID is not a valid number
                }
            }
            switch (state) {
                case SEARCHING -> {
                    if (trimmed.startsWith("BRK_EXCERPT_") && !trimmed.contains(" ")) {
                        try {
                            currentId = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                            state = State.EXPECTING_FILENAME;
                        } catch (NumberFormatException ignored) {
                            // expected when ID is not a valid number
                        }
                    }
                }
                case EXPECTING_FILENAME -> {
                    if (!trimmed.isEmpty()) {
                        Matcher m = fileLinePattern.matcher(trimmed);
                        if (m.matches()) {
                            currentFile = m.group(1).trim();
                            currentLineNum = Integer.parseInt(m.group(2));
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
                    if (line.equals("```")
                            || (line.startsWith("```") && line.substring(3).isBlank())) {
                        if (currentId != null && currentFile != null) {
                            results.put(
                                    currentId, new RawExcerpt(currentFile, currentLineNum, currentContent.toString()));
                        }
                        state = State.SEARCHING;
                    } else {
                        if (!currentContent.isEmpty()) {
                            currentContent.append("\n");
                        }
                        currentContent.append(line);
                    }
                }
            }
        }
        return Map.copyOf(results);
    }

    public record RawExcerpt(String file, int line, String excerpt) {}

    public record CodeExcerpt(ProjectFile file, @Nullable CodeUnit codeUnit, int line, DiffSide side, String excerpt) {}

    public record RawDesignFeedback(
            String title, String description, List<Integer> excerptIds, String recommendation) {}

    public record RawTacticalFeedback(String title, String description, int excerptId, String recommendation) {}

    public record RawReview(
            String overview,
            List<RawDesignFeedback> designNotes,
            List<RawTacticalFeedback> tacticalNotes,
            List<String> additionalTests) {
        public String toJson() {
            return Json.toJson(this);
        }

        public static RawReview fromJson(String json) {
            return Json.fromJson(json, RawReview.class);
        }
    }

    public record DesignFeedback(String title, String description, List<CodeExcerpt> excerpts, String recommendation) {}

    public record TacticalFeedback(String title, String description, CodeExcerpt excerpt, String recommendation) {}

    public record ReviewFeedback(String title, String description, String recommendation) {}

    public record GuidedReview(
            String overview,
            List<DesignFeedback> designNotes,
            List<TacticalFeedback> tacticalNotes,
            List<String> additionalTests) {

        public String toJson() {
            return Json.toJson(this);
        }

        public static GuidedReview fromJson(String json) {
            return Json.fromJson(json, GuidedReview.class);
        }

        public static GuidedReview fromRaw(RawReview rawReview, Map<Integer, CodeExcerpt> resolvedExcerpts) {
            List<DesignFeedback> designNotes = rawReview.designNotes().stream()
                    .map(raw -> new DesignFeedback(
                            raw.title(),
                            raw.description(),
                            raw.excerptIds().stream()
                                    .map(resolvedExcerpts::get)
                                    .filter(java.util.Objects::nonNull)
                                    .toList(),
                            raw.recommendation()))
                    .toList();

            List<TacticalFeedback> tacticalNotes = rawReview.tacticalNotes().stream()
                    .filter(raw -> resolvedExcerpts.containsKey(raw.excerptId()))
                    .map(raw -> new TacticalFeedback(
                            raw.title(),
                            raw.description(),
                            java.util.Objects.requireNonNull(resolvedExcerpts.get(raw.excerptId())),
                            raw.recommendation()))
                    .toList();

            return new GuidedReview(rawReview.overview(), designNotes, tacticalNotes, rawReview.additionalTests());
        }
    }
}
