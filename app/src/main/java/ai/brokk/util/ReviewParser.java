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
                    if (line.equals("```")
                            || (line.startsWith("```") && line.substring(3).isBlank())) {
                        state = State.SEARCHING;
                    }
                }
            }
        }
        return Map.copyOf(files);
    }

    public Map<Integer, RawExcerpt> parseExcerpts(String text) {
        Map<Integer, FileLine> files = parseExcerptFileLines(text);
        Map<Integer, String> contents = parseExcerptContents(text);
        Map<Integer, RawExcerpt> result = new HashMap<>();
        for (Integer id : files.keySet()) {
            if (contents.containsKey(id)) {
                FileLine fl = files.get(id);
                result.put(id, new RawExcerpt(fl.path(), fl.line(), contents.get(id)));
            }
        }
        return Map.copyOf(result);
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

    public record RawExcerpt(String file, int line, String excerpt) {}

    public record CodeExcerpt(
            ProjectFile file, @Nullable CodeUnit codeUnit, int line, DiffSide side, String excerpt) {}

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

        public static GuidedReview fromRaw(
                RawReview rawReview,
                Map<Integer, String> excerptContents,
                Map<Integer, String> excerptFiles,
                java.util.function.BiFunction<String, String, CodeExcerpt> resolver) {
            List<DesignFeedback> designNotes = rawReview.designNotes().stream()
                    .map(raw -> new DesignFeedback(
                            raw.title(),
                            raw.description(),
                            raw.excerptIds().stream()
                                    .filter(id -> id != null && id >= 0 && excerptContents.containsKey(id))
                                    .map(id -> resolver.apply(excerptFiles.get(id), excerptContents.get(id)))
                                    .toList(),
                            raw.recommendation()))
                    .toList();

            List<TacticalFeedback> tacticalNotes = rawReview.tacticalNotes().stream()
                    .map(raw -> {
                        CodeExcerpt excerpt = (raw.excerptId() >= 0 && excerptContents.containsKey(raw.excerptId()))
                                ? resolver.apply(
                                        excerptFiles.get(raw.excerptId()), excerptContents.get(raw.excerptId()))
                                : resolver.apply("unknown", "");
                        return new TacticalFeedback(raw.title(), raw.description(), excerpt, raw.recommendation());
                    })
                    .toList();

            return new GuidedReview(rawReview.overview(), designNotes, tacticalNotes, rawReview.additionalTests());
        }
    }
}
