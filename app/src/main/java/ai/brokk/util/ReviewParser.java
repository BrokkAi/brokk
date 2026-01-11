package ai.brokk.util;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    public sealed interface Segment permits TextSegment, ExcerptSegment {}

    public record TextSegment(String text) implements Segment {}

    public record ExcerptSegment(int id, String file, int line, String content) implements Segment {}

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
        return parseToSegments(text).stream()
                .filter(s -> s instanceof TextSegment)
                .map(s -> ((TextSegment) s).text())
                .collect(Collectors.joining())
                .trim();
    }

    public Map<Integer, RawExcerpt> parseExcerpts(String text) {
        Map<Integer, RawExcerpt> results = new HashMap<>();
        for (Segment segment : parseToSegments(text)) {
            if (segment instanceof ExcerptSegment es) {
                results.put(es.id(), new RawExcerpt(es.file(), es.line(), es.content()));
            }
        }
        return Map.copyOf(results);
    }

    private String cleanMetadata(String text) {
        // Unescape newlines before checking for orphaned BRK_EXCERPT markers
        String unescaped = text.replace("\\n", "\n");
        return unescaped
                .lines()
                .filter(line -> {
                    String trimmed = line.trim();
                    return !(trimmed.startsWith("BRK_EXCERPT_")
                            && !trimmed.contains(" ")
                            && trimmed.length() > "BRK_EXCERPT_".length());
                })
                .collect(Collectors.joining("\n"));
    }

    public List<Segment> parseToSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        State state = State.SEARCHING;

        StringBuilder textAccumulator = new StringBuilder();
        StringBuilder excerptAccumulator = new StringBuilder();

        Integer currentId = null;
        String currentFile = null;
        int currentLineNum = -1;
        Pattern fileLinePattern = Pattern.compile("^(.*)\\s+@(\\d+)$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (state == State.SEARCHING) {
                if (trimmed.startsWith("BRK_EXCERPT_")
                        && !trimmed.contains(" ")
                        && trimmed.length() > "BRK_EXCERPT_".length()
                        && trimmed.substring("BRK_EXCERPT_".length()).chars().allMatch(Character::isDigit)) {
                    int id = Integer.parseInt(trimmed.substring("BRK_EXCERPT_".length()));
                    // Look ahead for file, fence, and closing fence
                    if (i + 2 < lines.length) {
                        Matcher m = fileLinePattern.matcher(lines[i + 1].trim());
                        if (m.matches() && lines[i + 2].trim().startsWith("```")) {
                            // Find the closing fence before accepting this excerpt
                            if (findClosingFence(lines, i + 3) != -1) {
                                if (!textAccumulator.isEmpty()) {
                                    segments.add(new TextSegment(textAccumulator.toString()));
                                    textAccumulator.setLength(0);
                                }
                                currentId = id;
                                currentFile = m.group(1).trim();
                                currentLineNum = Integer.parseInt(m.group(2));
                                excerptAccumulator.setLength(0);
                                state = State.IN_CONTENT;
                                i += 2; // skip filename and fence
                                continue;
                            }
                        }
                    }
                }
                textAccumulator.append(line);
                if (i < lines.length - 1) {
                    textAccumulator.append("\n");
                }
            } else {
                // IN_CONTENT
                if (line.equals("```")
                        || (line.startsWith("```") && line.substring(3).isBlank())) {
                    segments.add(new ExcerptSegment(
                            Objects.requireNonNull(currentId),
                            Objects.requireNonNull(currentFile),
                            currentLineNum,
                            excerptAccumulator.toString()));

                    // If there is another line, append the newline following the fence to textAccumulator
                    if (i < lines.length - 1) {
                        textAccumulator.setLength(0);
                        textAccumulator.append("\n");
                    }
                    state = State.SEARCHING;
                } else {
                    if (!excerptAccumulator.isEmpty()) {
                        excerptAccumulator.append("\n");
                    }
                    excerptAccumulator.append(line);
                }
            }
        }

        if (!textAccumulator.isEmpty()) {
            segments.add(new TextSegment(textAccumulator.toString()));
        }

        return List.copyOf(segments);
    }

    /**
     * Finds the index of a closing fence (``` at start of line) starting from the given index.
     * Returns -1 if no closing fence is found or if another BRK_EXCERPT_ marker is encountered first.
     */
    private int findClosingFence(String[] lines, int startIndex) {
        for (int j = startIndex; j < lines.length; j++) {
            String l = lines[j];
            String trimmed = l.trim();
            // If we encounter another excerpt marker before finding a closing fence,
            // the current block is unclosed
            if (trimmed.startsWith("BRK_EXCERPT_")
                    && !trimmed.contains(" ")
                    && trimmed.length() > "BRK_EXCERPT_".length()
                    && trimmed.substring("BRK_EXCERPT_".length()).chars().allMatch(Character::isDigit)) {
                return -1;
            }
            if (l.equals("```") || (l.startsWith("```") && l.substring(3).isBlank())) {
                return j;
            }
        }
        return -1;
    }

    public String serializeSegments(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            if (segment instanceof TextSegment ts) {
                sb.append(ts.text());
            } else if (segment instanceof ExcerptSegment es) {
                sb.append("BRK_EXCERPT_").append(es.id()).append("\n");
                sb.append(es.file()).append(" @").append(es.line()).append("\n");
                sb.append("```\n");
                sb.append(es.content()).append("\n");
                sb.append("```");
            }
        }
        return sb.toString();
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
                            instance.cleanMetadata(raw.description()),
                            raw.excerptIds().stream()
                                    .map(resolvedExcerpts::get)
                                    .filter(Objects::nonNull)
                                    .toList(),
                            instance.cleanMetadata(raw.recommendation())))
                    .toList();

            List<TacticalFeedback> tacticalNotes = rawReview.tacticalNotes().stream()
                    .filter(raw -> resolvedExcerpts.containsKey(raw.excerptId()))
                    .map(raw -> new TacticalFeedback(
                            raw.title(),
                            instance.cleanMetadata(raw.description()),
                            Objects.requireNonNull(resolvedExcerpts.get(raw.excerptId())),
                            instance.cleanMetadata(raw.recommendation())))
                    .toList();

            return new GuidedReview(rawReview.overview(), designNotes, tacticalNotes, rawReview.additionalTests());
        }
    }
}
