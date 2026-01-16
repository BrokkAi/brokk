package ai.brokk.util;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.project.MainProject;
import com.google.common.base.Splitter;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Parses code excerpts from LLM responses within standard Markdown.
 * Expected format:
 * At `path/to/file.java` line 42:
 * ```[lang]
 * $excerptContent
 * ```
 */
@NullMarked
public class ReviewParser {
    private static final Logger logger = LogManager.getLogger(ReviewParser.class);

    public static final ReviewParser instance = new ReviewParser();

    private ReviewParser() {}

    public record ExcerptMatch(int line, DiffSide side, String matchedText) {}

    public static @Nullable ExcerptMatch matchExcerptInFile(RawExcerpt excerpt, FileDiff fileDiff) {
        // Try NEW content first
        String newContent = fileDiff.newText();
        var newMatch = matchExcerptInContent(excerpt, newContent);
        if (newMatch.isPresent()) {
            return new ExcerptMatch(
                    newMatch.get().line(), DiffSide.NEW, newMatch.get().matchedText());
        }

        // Try OLD content
        String oldContent = fileDiff.oldText();
        var oldMatch = matchExcerptInContent(excerpt, oldContent);
        if (oldMatch.isPresent()) {
            return new ExcerptMatch(
                    oldMatch.get().line(), DiffSide.OLD, oldMatch.get().matchedText());
        }

        return null;
    }

    public sealed interface Segment permits TextSegment, ExcerptSegment {}

    public record TextSegment(String text) implements Segment {}

    public record ExcerptSegment(String file, int line, String content) implements Segment {}

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

    public enum DiffSide {
        OLD,
        NEW
    }

    public List<RawExcerpt> parseExcerpts(String text) {
        return parseToSegments(text).stream()
                .filter(ExcerptSegment.class::isInstance)
                .map(ExcerptSegment.class::cast)
                .map(es -> new RawExcerpt(es.file(), es.line(), es.content()))
                .toList();
    }

    /**
     * Parses excerpts associated with specific IDs (e.g., "Excerpt 1:").
     */
    public Map<Integer, RawExcerpt> parseNumberedExcerpts(String text) {
        Map<Integer, RawExcerpt> results = new HashMap<>();
        // Look for "Excerpt N:" followed by an excerpt in the new format
        Pattern pattern = Pattern.compile("Excerpt\\s+(\\d+):", Pattern.CASE_INSENSITIVE);
        String[] parts = pattern.split(text, -1);
        Matcher matcher = pattern.matcher(text);

        int partIdx = 1; // parts[0] is everything before the first match
        while (matcher.find() && partIdx < parts.length) {
            int id = Integer.parseInt(matcher.group(1));
            List<RawExcerpt> excerptsInPart = parseExcerpts(parts[partIdx]);
            if (!excerptsInPart.isEmpty()) {
                results.put(id, excerptsInPart.getFirst());
            }
            partIdx++;
        }
        return results;
    }

    private String cleanMetadata(String text) {
        return text.replace("\\n", "\n");
    }

    // Pattern to match "At `filepath` line N:" format
    private static final Pattern AT_FILE_LINE_PATTERN =
            Pattern.compile("^At\\s+`([^`]+)`\\s+line\\s+(\\d+):\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Injects [Excerpt N] markers before each "At `file` line N:" header.
     */
    public String tagExcerpts(String text) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = Splitter.on(Pattern.compile("\\R")).splitToList(text);
        int excerptCount = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            Matcher atMatcher = AT_FILE_LINE_PATTERN.matcher(trimmed);
            if (atMatcher.matches()) {
                // Look ahead for code fence
                if (i + 1 < lines.size() && lines.get(i + 1).trim().startsWith("```")) {
                    sb.append("[Excerpt ").append(excerptCount++).append("]\n");
                }
            }
            sb.append(line);
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public List<Segment> parseToSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        List<String> lines = Splitter.on(Pattern.compile("\\R")).splitToList(text);

        StringBuilder textAccumulator = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Check for "At `filepath` line N:" pattern
            Matcher atMatcher = AT_FILE_LINE_PATTERN.matcher(trimmed);
            if (atMatcher.matches()) {
                // Look ahead for code fence on next line
                if (i + 1 < lines.size()) {
                    String nextLine = lines.get(i + 1);
                    String nextTrimmed = nextLine.trim();
                    if (nextLine.startsWith("```")
                            && (nextTrimmed.length() == 3 || !Character.isWhitespace(nextTrimmed.charAt(3)))) {
                        int closingIdx = findClosingFence(lines, i + 2);
                        if (closingIdx != -1) {
                            // Flush text accumulator (excluding the "At" line)
                            if (!textAccumulator.isEmpty()) {
                                segments.add(new TextSegment(textAccumulator.toString()));
                                textAccumulator.setLength(0);
                            }

                            String currentFile = atMatcher.group(1).trim();
                            int currentLineNum = Integer.parseInt(atMatcher.group(2));
                            StringBuilder content = new StringBuilder();
                            for (int j = i + 2; j < closingIdx; j++) {
                                if (!content.isEmpty()) content.append("\n");
                                content.append(lines.get(j));
                            }

                            String finalContent = content.toString();
                            segments.add(new ExcerptSegment(currentFile, currentLineNum, finalContent));

                            // After the closing fence, continue from the next line
                            i = closingIdx;
                            continue;
                        }
                    }
                }
            }

            textAccumulator.append(line);
            if (i < lines.size() - 1) {
                textAccumulator.append("\n");
            }
        }

        if (!textAccumulator.isEmpty()) {
            segments.add(new TextSegment(textAccumulator.toString()));
        }

        return List.copyOf(segments);
    }

    private int findClosingFence(List<String> lines, int startIndex) {
        // Limit lookahead to prevent performance issues or infinite loops on pathological input
        int maxLookahead = 1000;
        int end = Math.min(lines.size(), startIndex + maxLookahead);

        for (int j = startIndex; j < end; j++) {
            String l = lines.get(j);
            if (l.matches("^```\\s*$")) {
                return j;
            }
            // If we see another opening fence (with language), current block is unclosed
            if (l.startsWith("```") && l.trim().length() > 3) {
                return -1;
            }
            // If we see another "At `file` line N:" pattern, current block is unclosed
            if (AT_FILE_LINE_PATTERN.matcher(l.trim()).matches()) {
                return -1;
            }
        }
        return -1;
    }

    public String serializeSegments(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment instanceof TextSegment ts) {
                sb.append(ts.text());
            } else if (segment instanceof ExcerptSegment es) {
                sb.append("At `")
                        .append(es.file())
                        .append("` line ")
                        .append(es.line())
                        .append(":\n");
                sb.append("```\n");
                sb.append(es.content()).append("\n");
                sb.append("```");
                if (i < segments.size() - 1) {
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    public record RawExcerpt(String file, int line, String excerpt) {}

    public record CodeExcerpt(ProjectFile file, @Nullable CodeUnit codeUnit, int line, DiffSide side, String excerpt) {}

    public record RawDesignFeedback(
            String title, String description, List<Integer> excerptIndices, String recommendation) {}

    public record RawTacticalFeedback(String title, String description, int excerptIndex, String recommendation) {}

    public record RawReview(
            String overview,
            List<KeyChanges> keyChanges,
            List<RawDesignFeedback> designNotes,
            List<RawTacticalFeedback> tacticalNotes,
            List<TestFeedback> additionalTests) {
        public String toJson() {
            return Json.toJson(this);
        }

        public static RawReview fromJson(String json) {
            return Json.fromJson(json, RawReview.class);
        }
    }

    public record KeyChanges(String title, String description, List<CodeExcerpt> excerpts) {}

    public record DesignFeedback(String title, String description, List<CodeExcerpt> excerpts, String recommendation) {}

    public record TacticalFeedback(String title, String description, CodeExcerpt excerpt, String recommendation) {}

    public record TestFeedback(String title, String recommendation) {}

    public record Section(String type, String title, String content) {}

    public List<Section> parseIntoSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        Iterable<String> lines = Splitter.on(Pattern.compile("\\R")).split(markdown);

        String currentTopLevel = "Overview";
        String currentTitle = "Overview";
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                String content = currentContent.toString().trim();
                // Add previous section if it has content OR if it wasn't the initial Overview
                if (!content.isEmpty() || !currentTitle.equals(currentTopLevel)) {
                    sections.add(new Section(currentTopLevel, currentTitle, content));
                }

                currentTopLevel = trimmed.substring(3).trim();
                currentTitle = currentTopLevel;
                currentContent.setLength(0);
            } else if (trimmed.startsWith("### ")) {
                String content = currentContent.toString().trim();
                // If content is empty and we're switching from a top-level to a sub-header,
                // we don't need to save the empty top-level header as a separate section.
                if (!content.isEmpty() || !currentTitle.equals(currentTopLevel)) {
                    sections.add(new Section(currentTopLevel, currentTitle, content));
                }

                currentTitle = trimmed.substring(4).trim();
                currentContent.setLength(0);
            } else {
                if (!currentContent.isEmpty() || !line.isEmpty()) {
                    if (!currentContent.isEmpty()) {
                        currentContent.append("\n");
                    }
                    currentContent.append(line);
                }
            }
        }

        String finalContent = currentContent.toString().trim();
        if (!finalContent.isEmpty()
                || !currentTitle.equals("Overview")
                || (currentTitle.equals("Overview") && !sections.isEmpty())) {
            sections.add(new Section(currentTopLevel, currentTitle, finalContent));
        }

        // Final filter: remove the placeholder "Overview" if it ended up empty and other sections exist
        if (sections.size() > 1
                && sections.get(0).title().equals("Overview")
                && sections.get(0).content().isEmpty()) {
            sections.remove(0);
        }

        return List.copyOf(sections);
    }

    public String serializeSections(List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        String lastType = null;

        for (Section s : sections) {
            boolean emittedTypeHeader = false;
            if (!s.type().equals(lastType)) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append("## ").append(s.type());
                lastType = s.type();
                emittedTypeHeader = true;
            }

            if (!s.title().equals(s.type())) {
                // If we just emitted a type header, only add single newline before sub-header
                // Otherwise add double newline to separate from previous section's content
                if (emittedTypeHeader) {
                    sb.append("\n");
                } else if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("### ").append(s.title());
            }

            if (!s.content().isEmpty()) {
                sb.append("\n").append(s.content());
            }
        }
        return sb.toString().trim();
    }

    public record NoteValidationError(String title, String message) {}

    public List<NoteValidationError> validateParsedNotes(String markdown) {
        List<NoteValidationError> errors = new ArrayList<>();
        if (markdown.isBlank()) {
            return errors;
        }

        String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n");
        List<Segment> segments = parseToSegments(normalized);

        validateNotesInSection(normalized, segments, "Design Notes", false, errors);
        validateNotesInSection(normalized, segments, "Tactical Notes", true, errors);

        return List.copyOf(errors);
    }

    private void validateNotesInSection(
            String markdown,
            List<Segment> segments,
            String sectionName,
            boolean requireExcerpt,
            List<NoteValidationError> errors) {
        // Find section by scanning lines
        List<String> lines = Splitter.on('\n').splitToList(markdown);
        int sectionStart = -1;
        String sectionHeader = "## " + sectionName;

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(sectionHeader)) {
                sectionStart = i;
                break;
            }
        }

        if (sectionStart == -1) {
            return;
        }

        // Find section end (next ## header or end of file)
        int sectionEnd = lines.size();
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                sectionEnd = i;
                break;
            }
        }

        // Find all ### headers within this section
        for (int i = sectionStart + 1; i < sectionEnd; i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("### ")) {
                String title = trimmed.substring(4).trim();
                String noteContent = extractNoteSection(markdown, title);
                if (noteContent == null) continue;

                // Check recommendation
                String singularType =
                        sectionName.endsWith("s") ? sectionName.substring(0, sectionName.length() - 1) : sectionName;
                if (hasEmptyRecommendation(noteContent)) {
                    errors.add(new NoteValidationError(title, singularType + " has an empty recommendation."));
                } else if (!noteContent.contains("**Recommendation:**")) {
                    errors.add(new NoteValidationError(
                            title, singularType + " is missing a **Recommendation:** section."));
                }

                // Check excerpt for tactical notes
                if (requireExcerpt) {
                    boolean hasExcerpt = false;
                    for (Segment s : segments) {
                        if (s instanceof ExcerptSegment es
                                && noteContent.contains("`" + es.file() + "`")
                                && noteContent.contains("line " + es.line())) {
                            hasExcerpt = true;
                            break;
                        }
                    }
                    if (!hasExcerpt) {
                        errors.add(new NoteValidationError(
                                title, "Tactical note must have a code block with At `filepath` line N: format."));
                    }
                }
            }
        }
    }

    private static boolean hasEmptyRecommendation(String noteSection) {
        Pattern recPattern = Pattern.compile("\\*\\*Recommendation:\\*\\*\\s*(.*)$", Pattern.MULTILINE);
        Matcher m = recPattern.matcher(noteSection);
        if (m.find()) {
            // Check if there's any non-whitespace content after the marker on the same line
            // or on subsequent lines before the next section
            String afterMarker = m.group(1).trim();
            if (!afterMarker.isEmpty()) {
                return false;
            }
            // Check if there's content on subsequent lines (before next header)
            int endOfMatch = m.end();
            String rest = noteSection.substring(endOfMatch);
            String[] restLines = rest.split("\n", -1);
            for (String line : restLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("###") || trimmed.startsWith("##")) {
                    break;
                }
                if (!trimmed.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
        return false; // No recommendation marker found - different error
    }

    public static @Nullable String extractNoteSection(String markdown, String noteTitle) {
        List<String> lines = Splitter.on('\n').splitToList(markdown);
        int start = -1;
        Locale locale = Locale.ROOT;
        String headerLower = ("### " + noteTitle).toLowerCase(locale);

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().toLowerCase(locale).equals(headerLower)) {
                start = i;
                break;
            }
        }

        if (start == -1) return null;

        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("### ") || trimmed.startsWith("## ")) {
                end = i;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines.get(i));
            if (i < end - 1 || (end < lines.size())) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public GuidedReview parseMarkdownReview(String markdown, Map<Integer, CodeExcerpt> resolvedExcerpts) {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.UNDERSCORE_DELIMITER_PROCESSOR, false);
        Parser parser = Parser.builder(options).build();
        Node document = parser.parse(markdown);

        StringBuilder overviewBuilder = new StringBuilder();
        List<KeyChanges> keyChanges = new ArrayList<>();
        List<DesignFeedback> designNotes = new ArrayList<>();
        List<TacticalFeedback> tacticalNotes = new ArrayList<>();
        List<TestFeedback> additionalTests = new ArrayList<>();

        String currentTopLevelSection = "";

        // Get all excerpts in sequence to match them against nodes during parsing.
        // Track which global indices have been consumed to handle duplicates correctly.
        List<RawExcerpt> allExcerpts = parseExcerpts(markdown);
        var consumedIndices = new HashSet<Integer>();

        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                String headingText = heading.getText().toString().trim();

                if (heading.getLevel() == 2) {
                    currentTopLevelSection = headingText;
                    logger.debug("Parser transitioned to section: {}", currentTopLevelSection);
                } else if (heading.getLevel() == 3) {
                    ParsedContent content = parseFeedbackContent(heading);
                    List<CodeExcerpt> resolvedForNote = new ArrayList<>();
                    for (RawExcerpt raw : content.excerpts()) {
                        // Find the first unconsumed global index matching this excerpt
                        for (int i = 0; i < allExcerpts.size(); i++) {
                            if (consumedIndices.contains(i)) {
                                continue;
                            }
                            RawExcerpt global = allExcerpts.get(i);
                            if (global.file().equals(raw.file()) && global.line() == raw.line()) {
                                CodeExcerpt resolved = resolvedExcerpts.get(i);
                                if (resolved != null) {
                                    resolvedForNote.add(resolved);
                                }
                                consumedIndices.add(i);
                                break;
                            }
                        }
                    }

                    if (currentTopLevelSection.equalsIgnoreCase("Key Changes")) {
                        logger.debug("Parsing key change: {}", headingText);
                        keyChanges.add(
                                new KeyChanges(headingText, content.description(), List.copyOf(resolvedForNote)));
                    } else if (currentTopLevelSection.equalsIgnoreCase("Design Notes")) {
                        logger.debug("Parsing design note: {}", headingText);
                        designNotes.add(new DesignFeedback(
                                headingText,
                                content.description(),
                                List.copyOf(resolvedForNote),
                                content.recommendation()));
                    } else if (currentTopLevelSection.equalsIgnoreCase("Tactical Notes")) {
                        logger.debug("Parsing tactical note: {}", headingText);
                        if (!resolvedForNote.isEmpty()) {
                            tacticalNotes.add(new TacticalFeedback(
                                    headingText,
                                    content.description(),
                                    resolvedForNote.getFirst(),
                                    content.recommendation()));
                        } else if (!content.excerpts().isEmpty()) {
                            logger.warn(
                                    "Tactical note '{}' referenced excerpt {} but it could not be resolved",
                                    headingText,
                                    content.excerpts().getFirst());
                        }
                    } else if (currentTopLevelSection.equalsIgnoreCase("Additional Tests")) {
                        logger.debug("Parsing additional test: {}", headingText);
                        additionalTests.add(new TestFeedback(headingText, content.recommendation()));
                    }
                }
            } else if (currentTopLevelSection.equalsIgnoreCase("Overview")) {
                if (node instanceof Paragraph p) {
                    String pText = p.getChars().toString().trim();
                    if (!pText.isEmpty()) {
                        if (!overviewBuilder.isEmpty()) {
                            overviewBuilder.append("\n\n");
                        }
                        overviewBuilder.append(pText);
                    }
                } else if (node instanceof ListBlock lb) {
                    String listText = lb.getChars().toString().trim();
                    if (!listText.isEmpty()) {
                        if (!overviewBuilder.isEmpty()) {
                            overviewBuilder.append("\n\n");
                        }
                        overviewBuilder.append(listText);
                    }
                }
            }
        }

        return new GuidedReview(overviewBuilder.toString(), keyChanges, designNotes, tacticalNotes, additionalTests);
    }

    private record ParsedContent(String description, String recommendation, List<RawExcerpt> excerpts) {}

    private ParsedContent parseFeedbackContent(Heading heading) {
        StringBuilder description = new StringBuilder();
        StringBuilder recommendation = new StringBuilder();
        List<RawExcerpt> excerpts = new ArrayList<>();
        boolean inRecommendation = false;

        // Track pending "At `file` line N:" metadata for the next encountered code block
        String pendingFile = null;
        int pendingLine = -1;

        String recMarker = "**Recommendation:**";

        Node node = heading.getNext();
        while (node != null && !(node instanceof Heading h && h.getLevel() <= 3)) {
            String rawChars = node.getChars().toString();

            if (node instanceof Paragraph p) {
                String pText = p.getChars().toString();

                // Check for "At `filepath` line N:" within the paragraph.
                // If found, we update the pending metadata.
                Matcher atMatcher = AT_FILE_LINE_PATTERN.matcher("");
                for (String line : Splitter.on(Pattern.compile("\\R")).split(pText)) {
                    atMatcher.reset(line.trim());
                    if (atMatcher.matches()) {
                        pendingFile = atMatcher.group(1).trim();
                        pendingLine = Integer.parseInt(atMatcher.group(2));
                    }
                }

                String filtered = filterAtFileLines(pText);
                if (!inRecommendation && filtered.contains(recMarker)) {
                    inRecommendation = true;
                    int idx = filtered.indexOf(recMarker);
                    String beforeText = filtered.substring(0, idx).trim();
                    String afterText =
                            filtered.substring(idx + recMarker.length()).trim();

                    if (!beforeText.isEmpty()) {
                        description.append(beforeText).append("\n");
                    }
                    if (!afterText.isEmpty()) {
                        recommendation.append(afterText).append("\n");
                    }
                } else {
                    String trimmedFiltered = filtered.trim();
                    if (!trimmedFiltered.isEmpty()) {
                        if (inRecommendation) {
                            recommendation.append(trimmedFiltered).append("\n");
                        } else {
                            description.append(trimmedFiltered).append("\n");
                        }
                    }
                }
            } else if (node instanceof FencedCodeBlock fcb) {
                if (pendingFile != null) {
                    String content = fcb.getContentChars().toString();
                    if (content.endsWith("\n")) {
                        content = content.substring(0, content.length() - 1);
                    }
                    excerpts.add(new RawExcerpt(pendingFile, pendingLine, content));
                    // Reset pending metadata once consumed by a code block
                    pendingFile = null;
                    pendingLine = -1;
                }
            } else if (node instanceof ListBlock) {
                String filtered = filterAtFileLines(rawChars.trim());
                if (inRecommendation) {
                    recommendation.append(filtered).append("\n");
                } else {
                    description.append(filtered).append("\n");
                }
            }
            node = node.getNext();
        }

        return new ParsedContent(
                cleanMetadata(description.toString().trim()),
                cleanMetadata(recommendation.toString().trim()),
                excerpts);
    }

    private String filterAtFileLines(String text) {
        return text.lines()
                .filter(l -> !AT_FILE_LINE_PATTERN.matcher(l.trim()).matches())
                .collect(Collectors.joining("\n"));
    }

    public record GuidedReview(
            String overview,
            List<KeyChanges> keyChanges,
            List<DesignFeedback> designNotes,
            List<TacticalFeedback> tacticalNotes,
            List<TestFeedback> additionalTests) {

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
                            raw.excerptIndices().stream()
                                    .map(resolvedExcerpts::get)
                                    .filter(Objects::nonNull)
                                    .toList(),
                            instance.cleanMetadata(raw.recommendation())))
                    .toList();

            List<TacticalFeedback> tacticalNotes = rawReview.tacticalNotes().stream()
                    .map(raw -> {
                        CodeExcerpt excerpt = resolvedExcerpts.get(raw.excerptIndex());
                        if (excerpt == null) {
                            logger.warn(
                                    "Tactical note '{}' has missing excerpt index {}", raw.title(), raw.excerptIndex());
                        }
                        return new TacticalFeedback(
                                raw.title(),
                                instance.cleanMetadata(raw.description()),
                                Objects.requireNonNull(excerpt),
                                instance.cleanMetadata(raw.recommendation()));
                    })
                    .toList();

            return new GuidedReview(
                    rawReview.overview(),
                    rawReview.keyChanges(),
                    designNotes,
                    tacticalNotes,
                    rawReview.additionalTests());
        }
    }

    /**
     * Resolves raw excerpts from text into CodeExcerpts using a lightweight, trust-the-source approach.
     * <p>
     * This method parses excerpt code blocks and creates CodeExcerpt objects from the declared
     * file paths and line numbers. It attempts to match the excerpt text against the current file
     * content using whitespace-insensitive matching.
     * All excerpts are assigned {@link DiffSide#NEW} since the original side information is not preserved
     * in the serialized review format.
     *
     * FIXME: we should either only allow references to the NEW side, or preserve OLD side somehow so that we can
     * recover it. In both cases this method can go away and we can use the canonical resolver for loading from history.
     */
    public static Map<Integer, CodeExcerpt> resolveExcerptsNewOnly(ContextManager cm, String text) {
        List<RawExcerpt> raws = instance.parseExcerpts(text);
        Map<Integer, CodeExcerpt> resolved = new HashMap<>();

        for (int i = 0; i < raws.size(); i++) {
            RawExcerpt raw = raws.get(i);
            ProjectFile pf;
            try {
                pf = cm.toFile(raw.file());
            } catch (IllegalArgumentException e) {
                logger.debug("Failed to resolve file for excerpt: {}", raw.file());
                continue;
            }

            String content = pf.read().orElse(null);
            if (content == null) {
                logger.debug("Could not read file for excerpt: {}", raw.file());
                continue;
            }

            var matchOpt = matchExcerptInContent(raw, content);
            if (matchOpt.isEmpty()) {
                logger.debug("Excerpt text not found in file: {}", raw.file());
                continue;
            }

            var match = matchOpt.get();
            int lineCount = (int) match.matchedText().lines().count();
            CodeUnit unit = cm.getAnalyzerUninterrupted()
                    .enclosingCodeUnit(pf, match.line(), match.line() + Math.max(0, lineCount - 1))
                    .orElse(null);
            resolved.put(i, new CodeExcerpt(pf, unit, match.line(), DiffSide.NEW, match.matchedText()));
        }
        return resolved;
    }

    public static Optional<ExcerptMatchResult> matchExcerptInContent(RawExcerpt excerpt, String content) {
        List<String> excerptLines = Splitter.on(Pattern.compile("\\R")).splitToList(excerpt.excerpt());
        List<String> contentLines = Splitter.on(Pattern.compile("\\R")).splitToList(content);

        var matches = WhitespaceMatch.findAll(contentLines.toArray(new String[0]), excerptLines.toArray(new String[0]));
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        var best = findBestMatch(matches, excerpt.line());
        return Optional.of(new ExcerptMatchResult(best.startLine() + 1, best.matchedText()));
    }

    /**
     * Result of matching an excerpt in content, without side information.
     */
    public record ExcerptMatchResult(int line, String matchedText) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ReviewParser <markdown-file>");
            System.exit(1);
        }

        Path path = Path.of(args[0]);
        if (!Files.exists(path)) {
            System.err.println("File not found: " + path);
            System.exit(1);
        }

        String content = Files.readString(path);

        // Parse raw excerpts and resolve them to [non-validated] CodeExcerpts for structural validation/JSON preview
        Map<Integer, CodeExcerpt> resolvedExcerpts =
                resolveExcerptsNewOnly(new ContextManager(new MainProject(Path.of("."))), content);

        GuidedReview review = instance.parseMarkdownReview(content, resolvedExcerpts);
        System.out.println(review.toJson());
    }
}
