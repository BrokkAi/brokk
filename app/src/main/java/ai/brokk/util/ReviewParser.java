package ai.brokk.util;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Parses code excerpts from LLM responses within standard Markdown.
 * Expected format within a code block:
 * ```[lang]
 * path/to/file.java @line
 * $excerptContent
 * ```
 */
@NullMarked
public class ReviewParser {
    private static final Logger logger = LogManager.getLogger(ReviewParser.class);

    public static final ReviewParser instance = new ReviewParser();

    private ReviewParser() {}

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

    public String stripExcerpts(String text) {
        StringBuilder result = new StringBuilder();
        for (Segment s : parseToSegments(text)) {
            if (s instanceof TextSegment ts) {
                result.append(ts.text());
            } else if (s instanceof ExcerptSegment) {
                // Replace excerpt with a blank line to preserve paragraph separation
                result.append("\n");
            }
        }
        // Normalize multiple newlines to double newlines and trim
        return result.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    public List<RawExcerpt> parseExcerpts(String text) {
        List<RawExcerpt> results = new ArrayList<>();
        for (Segment segment : parseToSegments(text)) {
            if (segment instanceof ExcerptSegment es) {
                results.add(new RawExcerpt(es.file(), es.line(), es.content()));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Parses excerpts associated with specific IDs (e.g., "Excerpt 1:").
     */
    public Map<Integer, RawExcerpt> parseNumberedExcerpts(String text) {
        Map<Integer, RawExcerpt> results = new HashMap<>();
        // Look for "Excerpt N:" followed by a code block
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

    public List<Segment> parseToSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = text.split("\\R", -1);

        StringBuilder textAccumulator = new StringBuilder();
        // Ensure the filename part doesn't greedily eat the @ symbol
        // Must have non-whitespace filename, then whitespace, then @, then digits.
        Pattern fileLinePattern = Pattern.compile("^(\\S.*?)\\s+@(\\d+)$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Only start a block if line starts with ``` (not indented) and is either
            // exactly ``` or ```language (no space after ```)
            if (line.startsWith("```") && (trimmed.length() == 3 || !Character.isWhitespace(trimmed.charAt(3)))) {
                // Look ahead for file @line and closing fence
                if (i + 1 < lines.length) {
                    Matcher m = fileLinePattern.matcher(lines[i + 1].trim());
                    int closingIdx = findClosingFence(lines, i + 2, fileLinePattern);
                    if (m.matches() && closingIdx != -1) {
                        // Flush text accumulator, but preserve trailing newline for it
                        if (!textAccumulator.isEmpty()) {
                            segments.add(new TextSegment(textAccumulator.toString()));
                            textAccumulator.setLength(0);
                        }

                        String currentFile = m.group(1).trim();
                        int currentLineNum = Integer.parseInt(m.group(2));
                        StringBuilder content = new StringBuilder();
                        for (int j = i + 2; j < closingIdx; j++) {
                            if (!content.isEmpty()) content.append("\n");
                            content.append(lines[j]);
                        }

                        String finalContent = content.toString();
                        segments.add(new ExcerptSegment(currentFile, currentLineNum, finalContent));

                        // After the closing fence, continue from the next line
                        i = closingIdx;
                        continue;
                    }
                }
            }

            textAccumulator.append(line);
            if (i < lines.length - 1) {
                textAccumulator.append("\n");
            }
        }

        if (!textAccumulator.isEmpty()) {
            segments.add(new TextSegment(textAccumulator.toString()));
        }

        return List.copyOf(segments);
    }

    private int findClosingFence(String[] lines, int startIndex, Pattern fileLinePattern) {
        // Limit lookahead to prevent performance issues or infinite loops on pathological input
        int maxLookahead = 1000;
        int end = Math.min(lines.length, startIndex + maxLookahead);

        for (int j = startIndex; j < end; j++) {
            String l = lines[j];
            if (l.matches("^```\\s*$")) {
                // A closing fence must not be followed immediately by a file@line pattern,
                // which would indicate it's actually an opening fence for a new block.
                if (j + 1 < lines.length) {
                    String nextLine = lines[j + 1].trim();
                    if (fileLinePattern.matcher(nextLine).matches()) {
                        continue;
                    }
                    // Also skip if the next line looks like a bare filename without @line
                    // (indicating a malformed code block that we shouldn't close into)
                    if (looksLikeBareFilename(nextLine)) {
                        continue;
                    }
                }
                return j;
            }
            // If we see another opening fence (with language), current block is unclosed
            if (l.startsWith("```") && l.trim().length() > 3) {
                return -1;
            }
        }
        return -1;
    }

    private boolean looksLikeBareFilename(String line) {
        // A line looks like a bare filename (without @line) if it:
        // - Is non-empty and has no spaces (filenames typically don't have spaces)
        // - Looks like a path (contains / or \) OR has a file extension pattern
        // - Is NOT prose (prose typically has spaces and multiple words)
        if (line.isEmpty() || line.contains(" ")) {
            return false;
        }
        // Must look like a file path or have an extension
        // Pattern: either contains path separator, or ends with .ext
        if (line.contains("/") || line.contains("\\")) {
            return true;
        }
        // Check for file extension pattern: word.ext where ext is 1-4 chars
        return line.matches(".*\\.[a-zA-Z0-9]{1,4}$");
    }

    public String serializeSegments(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment instanceof TextSegment ts) {
                sb.append(ts.text());
            } else if (segment instanceof ExcerptSegment es) {
                sb.append("```\n");
                sb.append(es.file()).append(" @").append(es.line()).append("\n");
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
        String[] lines = markdown.split("\\R", -1);

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
        String[] lines = markdown.split("\n", -1);
        int sectionStart = -1;
        String sectionHeader = "## " + sectionName;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equalsIgnoreCase(sectionHeader)) {
                sectionStart = i;
                break;
            }
        }

        if (sectionStart == -1) {
            return;
        }

        // Find section end (next ## header or end of file)
        int sectionEnd = lines.length;
        for (int i = sectionStart + 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                sectionEnd = i;
                break;
            }
        }

        // Find all ### headers within this section
        for (int i = sectionStart + 1; i < sectionEnd; i++) {
            String trimmed = lines[i].trim();
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
                                && noteContent.contains(es.file())
                                && noteContent.contains("@" + es.line())) {
                            hasExcerpt = true;
                            break;
                        }
                    }
                    if (!hasExcerpt) {
                        errors.add(new NoteValidationError(
                                title, "Tactical note must have a code block starting with a file path."));
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
        String[] lines = markdown.split("\n", -1);
        int start = -1;
        java.util.Locale locale = java.util.Locale.ROOT;
        String headerLower = ("### " + noteTitle).toLowerCase(locale);

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().toLowerCase(locale).equals(headerLower)) {
                start = i;
                break;
            }
        }

        if (start == -1) return null;

        int end = lines.length;
        for (int i = start + 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("### ") || trimmed.startsWith("## ")) {
                end = i;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]);
            if (i < end - 1 || (end < lines.length)) {
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
        var consumedIndices = new java.util.HashSet<Integer>();

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
                } else if (node instanceof com.vladsch.flexmark.ast.ListBlock lb) {
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

        // Matches the filename @line line that starts a code block
        Pattern excerptMetadataPattern = Pattern.compile("^\\s*\\S+\\s+@\\d+\\s*$");
        String recMarker = "**Recommendation:**";

        Node node = heading.getNext();
        while (node != null && !(node instanceof Heading h && h.getLevel() <= 3)) {
            String rawChars = node.getChars().toString();

            if (node instanceof com.vladsch.flexmark.ast.FencedCodeBlock fcb) {
                String content = fcb.getContentChars().toString();
                // Remove trailing newline if present (flexmark often includes it)
                if (content.endsWith("\n")) {
                    content = content.substring(0, content.length() - 1);
                }
                String[] lines = content.split("\\R", -1);
                if (lines.length > 0) {
                    String firstLine = lines[0].trim();
                    Matcher m = excerptMetadataPattern.matcher(firstLine);
                    if (m.matches()) {
                        int atIdx = firstLine.lastIndexOf("@");
                        String filePath = firstLine.substring(0, atIdx).trim();
                        int lineNum =
                                Integer.parseInt(firstLine.substring(atIdx + 1).trim());
                        StringBuilder code = new StringBuilder();
                        for (int i = 1; i < lines.length; i++) {
                            if (!code.isEmpty()) code.append("\n");
                            code.append(lines[i]);
                        }
                        String finalContent = code.toString();
                        excerpts.add(new RawExcerpt(filePath, lineNum, finalContent));
                    }
                }
            }

            if (node instanceof Paragraph) {
                if (!inRecommendation && rawChars.contains(recMarker)) {
                    inRecommendation = true;
                    int idx = rawChars.indexOf(recMarker);
                    String beforeText = rawChars.substring(0, idx).trim();
                    String afterText =
                            rawChars.substring(idx + recMarker.length()).trim();

                    if (!beforeText.isEmpty()) {
                        description
                                .append(filterMetadataLines(beforeText, excerptMetadataPattern))
                                .append("\n");
                    }
                    if (!afterText.isEmpty()) {
                        recommendation
                                .append(filterMetadataLines(afterText, excerptMetadataPattern))
                                .append("\n");
                    }
                } else {
                    String filtered = filterMetadataLines(rawChars.trim(), excerptMetadataPattern);
                    if (inRecommendation) {
                        recommendation.append(filtered).append("\n");
                    } else {
                        description.append(filtered).append("\n");
                    }
                }
            } else if (node instanceof BulletList) {
                // For lists, we just append the raw chars to maintain formatting
                String filtered = filterMetadataLines(rawChars.trim(), excerptMetadataPattern);
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

    private String filterMetadataLines(String text, Pattern pattern) {
        return text.lines().filter(l -> !pattern.matcher(l).matches()).collect(Collectors.joining("\n"));
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
        List<RawExcerpt> raws = instance.parseExcerpts(content);
        Map<Integer, CodeExcerpt> resolvedExcerpts = new HashMap<>();
        Path root = Objects.requireNonNullElse(path.toAbsolutePath().getParent(), Path.of("."));

        for (int i = 0; i < raws.size(); i++) {
            RawExcerpt raw = raws.get(i);
            resolvedExcerpts.put(
                    i,
                    new CodeExcerpt(new ProjectFile(root, raw.file()), null, raw.line(), DiffSide.NEW, raw.excerpt()));
        }

        GuidedReview review = instance.parseMarkdownReview(content, resolvedExcerpts);
        System.out.println(review.toJson());
    }
}
