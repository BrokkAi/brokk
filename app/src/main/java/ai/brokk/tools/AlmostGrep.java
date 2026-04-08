package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;
import static ai.brokk.util.Lines.truncateLine;
import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

        SearchTools.FileContentSearchType effectiveSearchType =
                analyzer.getAnalyzedFiles().contains(file) ? searchType : SearchTools.FileContentSearchType.ALL;

        String output = effectiveSearchType == SearchTools.FileContentSearchType.ALL
                ? renderAllMatches(file, content, lineRefs, contextLines, analyzer, retainedMatchLines, filePath)
                : renderFilteredMatches(
                        file,
                        content,
                        lineRefs,
                        contextLines,
                        analyzer,
                        retainedMatchLines,
                        filePath,
                        effectiveSearchType);
        if (output.isEmpty()) {
            return null;
        }

        int visibleMatches = countVisibleMatches(file, content, analyzer, retainedMatchLines, effectiveSearchType);
        return visibleMatches == 0 ? null : new FileContentSearchResult(output, visibleMatches);
    }

    private static int countVisibleMatches(
            ProjectFile file,
            String content,
            IAnalyzer analyzer,
            Set<Integer> retainedMatchLines,
            SearchTools.FileContentSearchType searchType) {
        if (!analyzer.getAnalyzedFiles().contains(file) || searchType == SearchTools.FileContentSearchType.ALL) {
            return retainedMatchLines.size();
        }

        MatchBuckets buckets = classifyMatchLines(file, content, analyzer, retainedMatchLines);
        return switch (searchType) {
            case DECLARATIONS -> buckets.declarations().size();
            case USAGES -> buckets.usages().size();
            case ALL -> retainedMatchLines.size();
        };
    }

    private static String renderAllMatches(
            ProjectFile file,
            String content,
            List<LineRef> lineRefs,
            int contextLines,
            IAnalyzer analyzer,
            Set<Integer> retainedMatchLines,
            String filePath) {
        if (!analyzer.getAnalyzedFiles().contains(file)) {
            return renderFileBlock(
                    filePath,
                    lineRefs.size(),
                    renderSimpleSection("matches", retainedMatchLines, lineRefs, content, contextLines));
        }

        MatchBuckets buckets = classifyMatchLines(file, content, analyzer, retainedMatchLines);
        String matchesSection =
                renderMatchesSection(buckets.declarations(), buckets.usages(), lineRefs, content, contextLines);
        String relatedSection = renderSimpleSection("related", buckets.related(), lineRefs, content, contextLines);
        if (matchesSection.isEmpty() && relatedSection.isEmpty()) {
            return "";
        }
        return renderFileBlock(filePath, lineRefs.size(), matchesSection, relatedSection);
    }

    private static String renderFilteredMatches(
            ProjectFile file,
            String content,
            List<LineRef> lineRefs,
            int contextLines,
            IAnalyzer analyzer,
            Set<Integer> retainedMatchLines,
            String filePath,
            SearchTools.FileContentSearchType searchType) {
        if (!analyzer.getAnalyzedFiles().contains(file)) {
            return renderFileBlock(
                    filePath,
                    lineRefs.size(),
                    renderSimpleSection("matches", retainedMatchLines, lineRefs, content, contextLines));
        }

        MatchBuckets buckets = classifyMatchLines(file, content, analyzer, retainedMatchLines);
        Set<Integer> visibleLines = searchType == SearchTools.FileContentSearchType.DECLARATIONS
                ? buckets.declarations()
                : buckets.usages();
        if (visibleLines.isEmpty()) {
            return "";
        }

        String heading = searchType == SearchTools.FileContentSearchType.DECLARATIONS ? "[DECLARATIONS]" : "[USAGES]";
        String section = renderSimpleSection("matches", visibleLines, lineRefs, content, contextLines, heading);
        return renderFileBlock(filePath, lineRefs.size(), section);
    }

    private static MatchBuckets classifyMatchLines(
            ProjectFile file, String content, IAnalyzer analyzer, Set<Integer> retainedMatchLines) {
        Set<Integer> declarationLines = analyzer.getDeclarations(file).stream()
                .flatMap(cu -> analyzer.rangesOf(cu).stream())
                .map(range -> range.startLine() + 1)
                .collect(Collectors.toSet());
        Set<String> importLines = analyzer.importStatementsOf(file).stream()
                .flatMap(snippet -> snippet.lines().map(String::strip))
                .filter(line -> !line.isBlank())
                .collect(Collectors.toSet());

        Set<Integer> declarations = new LinkedHashSet<>();
        Set<Integer> usages = new LinkedHashSet<>();
        Set<Integer> related = new LinkedHashSet<>();

        retainedMatchLines.stream().sorted().forEach(lineNo -> {
            if (declarationLines.contains(lineNo)) {
                declarations.add(lineNo);
                return;
            }

            String lineText = lineText(content, lineNo);
            if (importLines.contains(lineText.strip()) || isLikelyImportLine(lineText)) {
                related.add(lineNo);
                return;
            }

            if (analyzer.enclosingCodeUnit(file, lineNo - 1, lineNo - 1).isPresent()) {
                usages.add(lineNo);
            } else {
                related.add(lineNo);
            }
        });

        return new MatchBuckets(declarations, usages, related);
    }

    private static String lineText(String content, int lineNo) {
        String[] lines = content.split("\\R", -1);
        if (lineNo < 1 || lineNo > lines.length) {
            return "";
        }
        return lines[lineNo - 1];
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
            Set<Integer> usageLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines) {
        List<String> parts = new ArrayList<>(2);
        if (!declarationLines.isEmpty()) {
            parts.add(renderSectionBody(declarationLines, lineRefs, content, contextLines, "[DECLARATIONS]"));
        }
        if (!usageLines.isEmpty()) {
            parts.add(renderSectionBody(usageLines, lineRefs, content, contextLines, "[USAGES]"));
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

    private static String renderSectionBody(
            Set<Integer> matchedLines,
            List<LineRef> lineRefs,
            String content,
            int contextLines,
            @Nullable String heading) {
        boolean[] toPrint = new boolean[lineRefs.size() + 1];
        matchedLines.forEach(lineNo -> {
            int from = max(1, lineNo - contextLines);
            int to = min(lineRefs.size(), lineNo + contextLines);
            for (int ln = from; ln <= to; ln++) {
                toPrint[ln] = true;
            }
        });

        List<String> lines = new ArrayList<>();
        if (heading != null) {
            lines.add(heading);
        }
        for (LineRef ref : lineRefs) {
            if (!toPrint[ref.lineNo()]) {
                continue;
            }
            lines.add(
                    "%d: %s".formatted(ref.lineNo(), truncateLine(content, ref.startInclusive(), ref.endExclusive())));
        }
        return String.join("\n", lines);
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

    private record MatchBuckets(Set<Integer> declarations, Set<Integer> usages, Set<Integer> related) {}

    record FileContentSearchResult(String output, int matches) {}

    private record FilePatternSearchResult(@Nullable ProjectFile match, @Nullable String error) {}
}
