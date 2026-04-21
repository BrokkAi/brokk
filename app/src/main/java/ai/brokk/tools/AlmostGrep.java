package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;
import static ai.brokk.util.Lines.truncateLine;
import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class AlmostGrep {
    private AlmostGrep() {}

    private static final Logger logger = LogManager.getLogger(AlmostGrep.class);
    private static final int FILE_SEARCH_BATCH_SIZE = 2 * SearchTools.FILE_SEARCH_LIMIT;

    static List<ProjectFile> findProjectTextFilesByGlob(Set<ProjectFile> allFiles, String globPattern) {
        PathMatcher matcher = compileGlobPathMatcher(globPattern);
        return allFiles.stream()
                .filter(ProjectFile::isText)
                .filter(file -> matcher.matches(Path.of(toUnixPath(file.toString()))))
                .sorted()
                .toList();
    }

    private static PathMatcher compileGlobPathMatcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + toUnixPath(pattern));
    }

    static SearchTools.FindFilesContainingResult findFilesContainingPatterns(
            List<Pattern> patterns, Set<ProjectFile> filesToSearch) throws InterruptedException {
        if (patterns.isEmpty()) {
            return new SearchTools.FindFilesContainingResult(Set.of(), List.of());
        }

        var orderedFiles = filesToSearch.stream().sorted().toList();
        var results = new ArrayList<FilePatternSearchResult>(orderedFiles.size());

        for (int start = 0; start < orderedFiles.size(); start += FILE_SEARCH_BATCH_SIZE) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted between file search batches");
            }

            var batch = orderedFiles.subList(start, min(start + FILE_SEARCH_BATCH_SIZE, orderedFiles.size()));

            final List<FilePatternSearchResult> batchResults;
            List<CompletableFuture<FilePatternSearchResult>> batchFutures = List.of();
            try {
                batchFutures = batch.stream()
                        .map(file -> LoggingFuture.supplyVirtual(() -> {
                            try {
                                if (!file.isText()) {
                                    return new FilePatternSearchResult(null, null);
                                }
                                var fileContentsOpt = file.read();
                                if (fileContentsOpt.isEmpty()) {
                                    return new FilePatternSearchResult(null, null);
                                }

                                String fileContents = fileContentsOpt.get();
                                boolean matched;
                                try {
                                    matched = patterns.stream().anyMatch(p -> findWithOverflowGuard(p, fileContents));
                                } catch (SearchTools.RegexMatchOverflowException e) {
                                    String message = "regex '%s' caused StackOverflowError".formatted(e.pattern());
                                    return new FilePatternSearchResult(null, file + ": " + message);
                                }

                                return matched
                                        ? new FilePatternSearchResult(file, null)
                                        : new FilePatternSearchResult(null, null);
                            } catch (Exception e) {
                                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                                return new FilePatternSearchResult(null, file + ": " + message);
                            }
                        }))
                        .toList();
                var batchDone = LoggingFuture.allOf(batchFutures.toArray(new CompletableFuture[0]));
                batchDone.get();
                batchResults =
                        batchFutures.stream().map(CompletableFuture::join).toList();
            } catch (InterruptedException e) {
                batchFutures.forEach(future -> future.cancel(true));
                throw e;
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return new SearchTools.FindFilesContainingResult(Set.of(), List.of(message));
            } catch (ExecutionException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return new SearchTools.FindFilesContainingResult(Set.of(), List.of(message));
            }

            results.addAll(batchResults);
        }

        var matches = results.stream()
                .map(FilePatternSearchResult::match)
                .filter(Objects::nonNull)
                .map(Objects::requireNonNull)
                .collect(Collectors.toSet());

        var errors = results.stream()
                .map(FilePatternSearchResult::error)
                .filter(Objects::nonNull)
                .map(Objects::requireNonNull)
                .toList();
        if (!errors.isEmpty()) {
            logger.warn("Errors searching file contents: {}", errors);
        }

        return new SearchTools.FindFilesContainingResult(matches, errors);
    }

    static @Nullable FileContentSearchResult searchFileContentsInFile(
            ProjectFile file,
            List<Pattern> patterns,
            int contextLines,
            IAnalyzer analyzer,
            SearchTools.FileContentSearchType searchType) {
        var contentOpt = file.read();
        if (contentOpt.isEmpty()) {
            return null;
        }

        String content = contentOpt.get();
        if (content.isEmpty()) {
            return null;
        }

        List<LineRef> lineRefs = computeLineRefs(content);
        if (lineRefs.isEmpty()) {
            return null;
        }
        int[] lineStartOffsets =
                lineRefs.stream().mapToInt(LineRef::startInclusive).toArray();
        int lineCount = lineRefs.size();
        String filePath = file.toString().replace('\\', '/');
        Set<Integer> retainedMatchLines = new LinkedHashSet<>();

        for (Pattern pattern : patterns) {
            byte[] lineStates = new byte[lineCount + 1];
            int matchesTaken = 0;

            try {
                Matcher matcher = pattern.matcher(content);
                int lineIdx = 0;
                while (matcher.find()) {
                    int matchStart = matcher.start();
                    while (lineIdx + 1 < lineCount && lineStartOffsets[lineIdx + 1] <= matchStart) {
                        lineIdx++;
                    }

                    int lineNo = lineIdx + 1;
                    if (lineStates[lineNo] == 0) {
                        if (matchesTaken < SearchTools.FILE_CONTENTS_MATCHES_PER_FILE) {
                            lineStates[lineNo] = 2;
                            matchesTaken++;
                            retainedMatchLines.add(lineNo);
                            if (matchesTaken >= SearchTools.FILE_CONTENTS_MATCHES_PER_FILE) {
                                break;
                            }
                        } else {
                            lineStates[lineNo] = 1;
                        }
                    }
                }
            } catch (StackOverflowError e) {
                throw new SearchTools.RegexMatchOverflowException(pattern.pattern(), e);
            }
        }

        if (retainedMatchLines.isEmpty()) {
            return null;
        }

        boolean analyzedFile = analyzer.getAnalyzedFiles().contains(file);
        SearchTools.FileContentSearchType effectiveSearchType =
                analyzedFile ? searchType : SearchTools.FileContentSearchType.ALL;
        String[] lineTexts = analyzedFile ? content.split("\\R", -1) : null;
        @Nullable
        MatchBuckets buckets = analyzedFile
                ? classifyMatchLines(file, Objects.requireNonNull(lineTexts), analyzer, retainedMatchLines)
                : null;

        String output = effectiveSearchType == SearchTools.FileContentSearchType.ALL
                ? renderAllMatches(content, lineRefs, contextLines, retainedMatchLines, filePath, buckets)
                : renderFilteredMatches(
                        content,
                        lineRefs,
                        contextLines,
                        filePath,
                        effectiveSearchType,
                        Objects.requireNonNull(buckets));
        if (output.isEmpty()) {
            return null;
        }

        int visibleMatches =
                switch (effectiveSearchType) {
                    case ALL -> retainedMatchLines.size();
                    case DECLARATIONS ->
                        Objects.requireNonNull(buckets).declarations().size();
                    case USAGES ->
                        Objects.requireNonNull(buckets).usageBlocksByLine().size();
                };
        return visibleMatches == 0 ? null : new FileContentSearchResult(output, visibleMatches);
    }

    private static String renderAllMatches(
            String content,
            List<LineRef> lineRefs,
            int contextLines,
            Set<Integer> retainedMatchLines,
            String filePath,
            @Nullable MatchBuckets buckets) {
        if (buckets == null) {
            return renderFileBlock(
                    filePath,
                    lineRefs.size(),
                    renderSimpleSection("matches", retainedMatchLines, lineRefs, content, contextLines));
        }

        String matchesSection = renderMatchesSection(
                buckets.declarations(), buckets.usageBlocksByLine(), lineRefs, content, contextLines);
        String relatedSection = renderSimpleSection("related", buckets.related(), lineRefs, content, contextLines);
        if (matchesSection.isEmpty() && relatedSection.isEmpty()) {
            return "";
        }
        return renderFileBlock(filePath, lineRefs.size(), matchesSection, relatedSection);
    }

    private static String renderFilteredMatches(
            String content,
            List<LineRef> lineRefs,
            int contextLines,
            String filePath,
            SearchTools.FileContentSearchType searchType,
            MatchBuckets buckets) {
        Set<Integer> visibleLines = searchType == SearchTools.FileContentSearchType.DECLARATIONS
                ? buckets.declarations()
                : buckets.usageBlocksByLine().keySet();
        if (visibleLines.isEmpty()) {
            return "";
        }

        String heading = searchType == SearchTools.FileContentSearchType.DECLARATIONS ? "[DECLARATIONS]" : "[USAGES]";
        String section = searchType == SearchTools.FileContentSearchType.DECLARATIONS
                ? renderSimpleSection("matches", visibleLines, lineRefs, content, contextLines, heading)
                : renderUsageSection(
                        "matches", buckets.usageBlocksByLine(), visibleLines, lineRefs, content, contextLines, heading);
        return renderFileBlock(filePath, lineRefs.size(), section);
    }

    private static MatchBuckets classifyMatchLines(
            ProjectFile file, String[] lineTexts, IAnalyzer analyzer, Set<Integer> retainedMatchLines) {
        Set<Integer> declarationLines = analyzer.getDeclarations(file).stream()
                .flatMap(cu -> analyzer.rangesOf(cu).stream())
                .map(range -> range.startLine() + 1)
                .collect(Collectors.toSet());
        Set<String> importLines = analyzer.importStatementsOf(file).stream()
                .flatMap(snippet -> snippet.lines().map(String::strip))
                .filter(line -> !line.isBlank())
                .collect(Collectors.toSet());

        Set<Integer> declarations = new LinkedHashSet<>();
        Map<Integer, EnclosingUsageBlock> usageBlocksByLine = new LinkedHashMap<>();
        Set<Integer> related = new LinkedHashSet<>();

        retainedMatchLines.stream().sorted().forEach(lineNo -> {
            if (declarationLines.contains(lineNo)) {
                declarations.add(lineNo);
                return;
            }

            String lineText = lineText(lineTexts, lineNo);
            if (importLines.contains(lineText.strip()) || isLikelyImportLine(lineText)) {
                related.add(lineNo);
                return;
            }

            if (analyzer.enclosingCodeUnit(file, lineNo - 1, lineNo - 1).isPresent()) {
                usageBlocksByLine.put(lineNo, resolveEnclosingUsageBlock(file, analyzer, lineNo));
            } else {
                related.add(lineNo);
            }
        });

        return new MatchBuckets(declarations, usageBlocksByLine, related);
    }

    private static String lineText(String[] lineTexts, int lineNo) {
        if (lineNo < 1 || lineNo > lineTexts.length) {
            return "";
        }
        return lineTexts[lineNo - 1];
    }

    private static boolean isLikelyImportLine(String lineText) {
        String trimmed = lineText.stripLeading();
        return trimmed.startsWith("import ")
                || trimmed.startsWith("from ")
                || trimmed.startsWith("#include")
                || trimmed.startsWith("using ")
                || trimmed.startsWith("require(");
    }

    private static String renderFileBlock(String filePath, int lineCount, String... sections) {
        List<String> nonBlankSections = new ArrayList<>();
        for (String section : sections) {
            if (!section.isBlank()) {
                nonBlankSections.add(section.stripTrailing());
            }
        }
        if (nonBlankSections.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        result.append("<file path=\"")
                .append(filePath)
                .append("\" loc=\"")
                .append(lineCount)
                .append("\">\n");
        result.append(String.join("\n", nonBlankSections)).append("\n");
        result.append("</file>");
        return result.toString();
    }

    private static String renderMatchesSection(
            Set<Integer> declarationLines,
            Map<Integer, EnclosingUsageBlock> usageBlocksByLine,
            List<LineRef> lineRefs,
            String content,
            int contextLines) {
        List<String> parts = new ArrayList<>(2);
        if (!declarationLines.isEmpty()) {
            parts.add(renderSectionBody(declarationLines, lineRefs, content, contextLines, "[DECLARATIONS]"));
        }
        if (!usageBlocksByLine.isEmpty()) {
            parts.add(renderUsageSectionBody(usageBlocksByLine, lineRefs, content, contextLines, "[USAGES]"));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "<matches>\n" + String.join("\n", parts) + "\n</matches>";
    }

    private static String renderSimpleSection(
            String tag, Set<Integer> matchedLines, List<LineRef> lineRefs, String content, int contextLines) {
        return renderSimpleSection(tag, matchedLines, lineRefs, content, contextLines, null);
    }

    private static String renderSimpleSection(
            String tag,
            Set<Integer> matchedLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            @Nullable String heading) {
        if (matchedLines.isEmpty()) {
            return "";
        }

        String body = renderSectionBody(matchedLines, lineRefs, content, contextLines, heading);
        return "<%s>\n%s\n</%s>".formatted(tag, body, tag);
    }

    private static String renderUsageSection(
            String tag,
            Map<Integer, EnclosingUsageBlock> usageBlocksByLine,
            Set<Integer> matchedLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            @Nullable String heading) {
        if (matchedLines.isEmpty()) {
            return "";
        }

        String body = renderUsageSectionBody(usageBlocksByLine, lineRefs, content, contextLines, heading);
        return "<%s>\n%s\n</%s>".formatted(tag, body, tag);
    }

    private static String renderSectionBody(
            Set<Integer> matchedLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            @Nullable String heading) {
        List<String> lines = new ArrayList<>();
        if (heading != null) {
            lines.add(heading);
        }
        lines.addAll(renderLines(matchedLines, lineRefs, content, contextLines, 1, lineRefs.size()));
        return String.join("\n", lines);
    }

    private static String renderUsageSectionBody(
            Map<Integer, EnclosingUsageBlock> usageBlocksByLine,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            @Nullable String heading) {
        Map<EnclosingUsageBlock, Set<Integer>> linesByBlock = new LinkedHashMap<>();
        usageBlocksByLine.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> linesByBlock
                .computeIfAbsent(entry.getValue(), ignored -> new LinkedHashSet<>())
                .add(entry.getKey()));

        List<String> lines = new ArrayList<>();
        if (heading != null) {
            lines.add(heading);
        }
        linesByBlock.forEach((block, blockLines) -> {
            lines.add("%s [%d..%d]".formatted(block.symbol(), block.startLine(), block.endLine()));
            lines.addAll(renderLines(blockLines, lineRefs, content, contextLines, block.startLine(), block.endLine()));
        });
        return String.join("\n", lines);
    }

    private static List<String> renderLines(
            Set<Integer> matchedLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            int startLine,
            int endLine) {
        if (matchedLines.isEmpty() || startLine > endLine) {
            return List.of();
        }

        boolean[] toPrint = new boolean[lineRefs.size() + 1];
        matchedLines.forEach(lineNo -> {
            int from = max(startLine, lineNo - contextLines);
            int to = min(endLine, lineNo + contextLines);
            for (int ln = from; ln <= to; ln++) {
                toPrint[ln] = true;
            }
        });

        return lineRefs.stream()
                .filter(ref -> ref.lineNo() >= startLine && ref.lineNo() <= endLine)
                .filter(ref -> toPrint[ref.lineNo()])
                .map(ref -> "%d: %s"
                        .formatted(ref.lineNo(), truncateLine(content, ref.startInclusive(), ref.endExclusive())))
                .toList();
    }

    private static EnclosingUsageBlock resolveEnclosingUsageBlock(ProjectFile file, IAnalyzer analyzer, int lineNo) {
        int zeroBasedLine = lineNo - 1;
        var enclosingUnit =
                analyzer.enclosingCodeUnit(file, zeroBasedLine, zeroBasedLine).orElse(null);
        if (enclosingUnit == null) {
            return new EnclosingUsageBlock("Line " + lineNo, lineNo, lineNo);
        }

        var enclosingRange = analyzer.rangesOf(enclosingUnit).stream()
                .filter(range -> zeroBasedLine >= range.startLine() && zeroBasedLine <= range.endLine())
                .min(Comparator.comparingInt(range -> range.endLine() - range.startLine()))
                .orElse(null);
        int startLine = enclosingRange == null ? lineNo : enclosingRange.startLine() + 1;
        int endLine = enclosingRange == null ? lineNo : enclosingRange.endLine() + 1;
        return new EnclosingUsageBlock(formatCodeUnitLabel(enclosingUnit), startLine, endLine);
    }

    private static String formatCodeUnitLabel(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int memberSeparator = shortName.lastIndexOf('.');
        if (memberSeparator < 0) {
            return shortName;
        }
        return shortName.substring(0, memberSeparator) + "::" + shortName.substring(memberSeparator + 1);
    }

    private static List<LineRef> computeLineRefs(String content) {
        if (content.isEmpty()) return List.of();

        int length = content.length();
        int lineNo = 1;
        int lineStart = 0;

        List<LineRef> refs = new ArrayList<>();

        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c != '\n' && c != '\r') {
                i++;
                continue;
            }

            int lineEnd = i;
            int next = i + 1;
            if (c == '\r' && next < length && content.charAt(next) == '\n') {
                next++;
            }

            refs.add(new LineRef(lineNo, lineStart, lineEnd));

            lineNo++;
            lineStart = next;
            i = next;
        }

        if (lineStart < length) {
            refs.add(new LineRef(lineNo, lineStart, length));
        }

        return List.copyOf(refs);
    }

    private static boolean findWithOverflowGuard(Pattern pattern, String input) {
        try {
            return pattern.matcher(input).find();
        } catch (StackOverflowError e) {
            throw new SearchTools.RegexMatchOverflowException(pattern.pattern(), e);
        }
    }

    private record LineRef(int lineNo, int startInclusive, int endExclusive) {}

    private record EnclosingUsageBlock(String symbol, int startLine, int endLine) {}

    private record MatchBuckets(
            Set<Integer> declarations, Map<Integer, EnclosingUsageBlock> usageBlocksByLine, Set<Integer> related) {}

    record FileContentSearchResult(String output, int matches) {}

    private record FilePatternSearchResult(@Nullable ProjectFile match, @Nullable String error) {}
}
