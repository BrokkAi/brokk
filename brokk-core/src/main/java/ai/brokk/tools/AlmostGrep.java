package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;
import static ai.brokk.util.Lines.truncateLine;
import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
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

    static List<FileContentSearchResult> searchFileContentsInFile(
            ProjectFile file, List<Pattern> patterns, int contextLines) {
        var contentOpt = file.read();
        if (contentOpt.isEmpty()) {
            return List.of();
        }

        String content = contentOpt.get();
        if (content.isEmpty()) {
            return List.of();
        }

        List<LineRef> lineRefs = null;
        int[] lineStartOffsets = null;
        int lineCount = 0;
        String filePath = file.toString().replace('\\', '/');
        List<FileContentSearchResult> patternResults = new ArrayList<>();

        for (Pattern pattern : patterns) {
            // 0: unseen, 1: seen but not retained (beyond first-N limit), 2: retained as one of first-N
            byte[] lineStates = new byte[lineCount + 1];
            int matchesTaken = 0;
            int totalMatchesInPattern = 0;

            try {
                Matcher matcher = pattern.matcher(content);
                int lineIdx = 0;
                while (matcher.find()) {
                    if (lineRefs == null) {
                        lineRefs = computeLineRefs(content);
                        if (lineRefs.isEmpty()) {
                            return List.of();
                        }
                        lineStartOffsets = lineRefs.stream()
                                .mapToInt(LineRef::startInclusive)
                                .toArray();
                        lineCount = lineRefs.size();
                        lineStates = new byte[lineCount + 1];
                    }
                    int[] nonNullLineStartOffsets = Objects.requireNonNull(lineStartOffsets);
                    int matchStart = matcher.start();
                    while (lineIdx + 1 < lineCount && nonNullLineStartOffsets[lineIdx + 1] <= matchStart) {
                        lineIdx++;
                    }

                    int lineNo = lineIdx + 1;
                    if (lineStates[lineNo] == 0) {
                        totalMatchesInPattern++;
                        if (matchesTaken < SearchTools.FILE_CONTENTS_MATCHES_PER_FILE) {
                            lineStates[lineNo] = 2;
                            matchesTaken++;
                        } else {
                            lineStates[lineNo] = 1;
                        }
                    }
                }
            } catch (StackOverflowError e) {
                throw new SearchTools.RegexMatchOverflowException(pattern.pattern(), e);
            }

            if (matchesTaken == 0) {
                continue;
            }
            List<LineRef> nonNullLineRefs = Objects.requireNonNull(lineRefs);

            boolean[] toPrint = new boolean[lineCount + 1];
            for (int lineNo = 1; lineNo <= lineCount; lineNo++) {
                if (lineStates[lineNo] != 2) {
                    continue;
                }
                int from = max(1, lineNo - contextLines);
                int to = min(lineCount, lineNo + contextLines);
                for (int ln = from; ln <= to; ln++) {
                    toPrint[ln] = true;
                }
            }

            List<String> outLines = new ArrayList<>();
            for (LineRef ref : nonNullLineRefs) {
                if (!toPrint[ref.lineNo()]) continue;
                outLines.add("%d: %s"
                        .formatted(ref.lineNo(), truncateLine(content, ref.startInclusive(), ref.endExclusive())));
            }

            String matchCountLabel = "first %d/%d matches".formatted(matchesTaken, totalMatchesInPattern);
            String header =
                    "%s (%d loc) (pattern: %s) (%s)".formatted(filePath, lineCount, pattern.pattern(), matchCountLabel);

            List<String> resultLines = new ArrayList<>(outLines.size() + 1);
            resultLines.add(header);
            resultLines.addAll(outLines);
            patternResults.add(new FileContentSearchResult(String.join("\n", resultLines), matchesTaken));
        }

        return List.copyOf(patternResults);
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

    record FileContentSearchResult(String output, int matches) {}

    private record FilePatternSearchResult(@Nullable ProjectFile match, @Nullable String error) {}
}
