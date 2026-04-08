package ai.brokk.tools;

import ai.brokk.EditBlock;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoData;
import com.github.difflib.DiffUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SearchReplaceBlockFormatter {
    private SearchReplaceBlockFormatter() {}

    public static SequencedSet<EditBlock.SearchReplaceBlock> fromChangedFiles(
            Map<ProjectFile, String> originalFileContents, Function<ProjectFile, String> revisedTextProvider) {
        var results = new LinkedHashSet<EditBlock.SearchReplaceBlock>();
        var sortedFiles = originalFileContents.keySet().stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();

        for (var file : sortedFiles) {
            var original = originalFileContents.getOrDefault(file, "");
            var revised = revisedTextProvider.apply(file);
            if (Objects.equals(original, revised)) {
                continue;
            }
            if (original.isBlank()) {
                results.add(new EditBlock.SearchReplaceBlock(file.toString(), "BRK_ENTIRE_FILE", revised));
                continue;
            }
            results.addAll(diffSingleFile(file.toString(), original, revised));
        }

        return results;
    }

    public static SequencedSet<EditBlock.SearchReplaceBlock> fromFileDiffs(List<GitRepoData.FileDiff> fileDiffs) {
        var results = new LinkedHashSet<EditBlock.SearchReplaceBlock>();
        var sortedDiffs = fileDiffs.stream()
                .sorted(Comparator.comparing(SearchReplaceBlockFormatter::sortKey))
                .toList();

        for (var diff : sortedDiffs) {
            if (isRename(diff)) {
                var oldFile = Objects.requireNonNull(diff.oldFile());
                var newFile = Objects.requireNonNull(diff.newFile());
                results.add(new EditBlock.SearchReplaceBlock(oldFile.toString(), diff.oldText(), ""));
                results.add(new EditBlock.SearchReplaceBlock(newFile.toString(), "BRK_ENTIRE_FILE", diff.newText()));
                continue;
            }

            if (diff.oldFile() == null) {
                var newFile = Objects.requireNonNull(diff.newFile());
                results.add(new EditBlock.SearchReplaceBlock(newFile.toString(), "BRK_ENTIRE_FILE", diff.newText()));
                continue;
            }

            if (diff.newFile() == null) {
                results.add(new EditBlock.SearchReplaceBlock(diff.oldFile().toString(), diff.oldText(), ""));
                continue;
            }

            results.addAll(diffSingleFile(diff.newFile().toString(), diff.oldText(), diff.newText()));
        }

        return results;
    }

    private static String sortKey(GitRepoData.FileDiff diff) {
        if (diff.newFile() != null) {
            return diff.newFile().toString();
        }
        if (diff.oldFile() != null) {
            return diff.oldFile().toString();
        }
        return "";
    }

    private static boolean isRename(GitRepoData.FileDiff diff) {
        return diff.oldFile() != null
                && diff.newFile() != null
                && !diff.oldFile().toString().equals(diff.newFile().toString());
    }

    private static SequencedSet<EditBlock.SearchReplaceBlock> diffSingleFile(
            String path, String original, String revised) {
        var results = new LinkedHashSet<EditBlock.SearchReplaceBlock>();
        var originalLines = Arrays.asList(original.split("\n", -1));
        var revisedLines = revised.isEmpty() ? List.<String>of() : Arrays.asList(revised.split("\n", -1));

        try {
            var patch = DiffUtils.diff(originalLines, revisedLines);

            record Window(int start, int end) {
                Window expandLeft() {
                    return new Window(Math.max(0, start - 1), end);
                }

                Window expandRight(int max) {
                    return new Window(start, Math.min(max, end + 1));
                }
            }

            var windows = new ArrayList<Window>();
            for (var delta : patch.getDeltas()) {
                var src = delta.getSource();
                int sPos = src.getPosition();
                int sSize = src.size();

                int wStart;
                int wEnd;
                if (sSize > 0) {
                    wStart = sPos;
                    wEnd = sPos + sSize - 1;
                } else {
                    wStart = sPos == 0 ? 0 : sPos - 1;
                    wEnd = wStart;
                }
                if (!originalLines.isEmpty()) {
                    wStart = Math.max(0, Math.min(wStart, originalLines.size() - 1));
                    wEnd = Math.max(0, Math.min(wEnd, originalLines.size() - 1));
                }
                windows.add(new Window(wStart, wEnd));
            }

            int lastIdx = Math.max(0, originalLines.size() - 1);
            windows = windows.stream()
                    .map(window -> {
                        var current = window;
                        var before = joinLines(originalLines, current.start, current.end);
                        while (!before.isEmpty()
                                && countOccurrences(original, before) > 1
                                && (current.start > 0 || current.end < lastIdx)) {
                            if (current.start > 0) {
                                current = current.expandLeft();
                            }
                            if (current.end < lastIdx) {
                                current = current.expandRight(lastIdx);
                            }
                            before = joinLines(originalLines, current.start, current.end);
                        }
                        return current;
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            windows.sort(Comparator.comparingInt(Window::start));
            var merged = new ArrayList<Window>();
            for (var window : windows) {
                if (merged.isEmpty()) {
                    merged.add(window);
                    continue;
                }
                var last = merged.getLast();
                if (window.start <= last.end + 1) {
                    merged.set(merged.size() - 1, new Window(last.start, Math.max(last.end, window.end)));
                } else {
                    merged.add(window);
                }
            }

            record DeltaShape(int pos, int size, int net) {}
            var shapes = patch.getDeltas().stream()
                    .map(delta -> new DeltaShape(
                            delta.getSource().getPosition(),
                            delta.getSource().size(),
                            delta.getTarget().size() - delta.getSource().size()))
                    .sorted(Comparator.comparingInt(DeltaShape::pos))
                    .toList();

            for (var window : merged) {
                int netBeforeStart = shapes.stream()
                        .filter(shape -> shape.pos + shape.size <= window.start)
                        .mapToInt(DeltaShape::net)
                        .sum();
                int revisedStart = window.start + netBeforeStart;

                int windowLen = window.end - window.start + 1;
                int netInWindow = 0;
                for (var delta : patch.getDeltas()) {
                    int pos = delta.getSource().getPosition();
                    int size = delta.getSource().size();
                    int net = delta.getTarget().size() - size;
                    boolean overlaps;
                    if (size > 0) {
                        overlaps = pos < (window.end + 1) && (pos + size) > window.start;
                    } else {
                        overlaps = pos >= window.start && pos <= (window.end + 1);
                    }
                    if (overlaps) {
                        netInWindow += net;
                    }
                }
                int revisedEnd = revisedStart + windowLen + netInWindow - 1;

                var before = joinLines(originalLines, window.start, window.end);
                var after = joinLines(
                        revisedLines,
                        clamp(revisedStart, 0, Math.max(0, revisedLines.size() - 1)),
                        clamp(revisedEnd, 0, Math.max(0, revisedLines.size() - 1)));

                if (!before.isEmpty() && countOccurrences(original, before) > 1) {
                    results.add(new EditBlock.SearchReplaceBlock(path, original, revised));
                    continue;
                }

                results.add(new EditBlock.SearchReplaceBlock(path, before, after));
            }
        } catch (Exception e) {
            results.add(new EditBlock.SearchReplaceBlock(path, original, revised));
        }

        return results;
    }

    private static String joinLines(List<String> lines, int start, int end) {
        if (lines.isEmpty() || start > end) {
            return "";
        }
        var joiner = new StringJoiner("\n");
        for (int i = start; i <= end; i++) {
            joiner.add(lines.get(i));
        }
        return joiner.toString();
    }

    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
