package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Apply line-based edits described by LineEdit objects to the workspace.
 *
 * <p>This class operates on the internal representation of edits. See {@link LineEdit} for details
 * on how external typed tags (e.g., `type="insert"`) are mapped to this model.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>Lines are 1-based and the replacement range is inclusive.
 *   <li>Insertion: `endLine < beginLine`. Insert at index (`beginLine` - 1). Valid insertion
 *       positions are 1..(n + 1); inserting into a missing file is allowed (file is created).
 *   <li>Replacement: `beginLine <= endLine`. File must already exist (strict option A). Range must
 *       satisfy 1 <= `beginLine` <= `endLine` <= n.
 * </ul>
 *
 * File content is written with a trailing newline if non-empty.
 */
public final class LineEditor {
    private static final Logger logger = LogManager.getLogger(LineEditor.class);

    private LineEditor() {
        // utility
    }

    public enum FailureReason {
        FILE_NOT_FOUND,
        INVALID_LINE_RANGE,
        IO_ERROR
    }

    public record FailedEdit(LineEdit edit, FailureReason reason, String commentary) {}

    public record ApplyResult(Map<ProjectFile, String> originalContents, List<FailedEdit> failures) {
        public boolean hadSuccessfulEdits() {
            return !originalContents.isEmpty();
        }

        public java.util.Set<ProjectFile> changedFiles() {
            return originalContents.keySet();
        }
    }

    /**
     * Apply a sequence of edits. Non-fatal failures are recorded and subsequent edits continue to apply.
     */
    public static ApplyResult applyEdits(IContextManager cm, IConsoleIO io, Collection<? extends LineEdit> edits) {
        var originals = new HashMap<ProjectFile, String>();
        var failures = new ArrayList<FailedEdit>();

        // Partition edits: group EditFile per file; collect DeleteFile preserving original order.
        var editsByFile = new HashMap<ProjectFile, List<LineEdit.EditFile>>();
        var deletes = new ArrayList<LineEdit.DeleteFile>();

        for (var edit : edits) {
            if (edit instanceof LineEdit.EditFile ef) {
                editsByFile.computeIfAbsent(ef.file(), k -> new ArrayList<>()).add(ef);
            } else if (edit instanceof LineEdit.DeleteFile df) {
                deletes.add(df);
            } else {
                failures.add(new FailedEdit(
                        edit, FailureReason.IO_ERROR, "Unknown edit type: " + edit.getClass().getSimpleName()));
            }
        }

        // Apply per file:
        //   1) replacements/deletes (begin <= end) sorted descending (bottom-to-top)
        //   2) insertions (end < begin) in original input order
        for (var entry : editsByFile.entrySet()) {
            var perFile = entry.getValue();

            var changes = new ArrayList<LineEdit.EditFile>();
            var inserts = new ArrayList<LineEdit.EditFile>();

            for (var ef : perFile) {
                if (ef.endLine() < ef.beginLine()) inserts.add(ef);
                else changes.add(ef);
            }

            changes.sort((a, b) -> {
                int c = Integer.compare(b.beginLine(), a.beginLine());
                if (c != 0) return c;
                return Integer.compare(b.endLine(), a.endLine());
            });

            // 1) Apply bottom-to-top range edits
            for (var ef : changes) {
                try {
                    applyEdit(ef, originals, failures);
                } catch (IOException e) {
                    var msg = java.util.Objects.requireNonNullElse(e.getMessage(), e.toString());
                    logger.error("IO error applying {}: {}", describe(ef), msg);
                    failures.add(new FailedEdit(ef, FailureReason.IO_ERROR, msg));
                }
            }

            // 2) Apply insertions in original order
            for (var ef : inserts) {
                try {
                    applyEdit(ef, originals, failures);
                } catch (IOException e) {
                    var msg = java.util.Objects.requireNonNullElse(e.getMessage(), e.toString());
                    logger.error("IO error applying {}: {}", describe(ef), msg);
                    failures.add(new FailedEdit(ef, FailureReason.IO_ERROR, msg));
                }
            }
        }

        // Then apply deletes in their original order.
        for (var df : deletes) {
            try {
                applyDelete(cm, io, df, originals, failures);
            } catch (IOException e) {
                var msg = java.util.Objects.requireNonNullElse(e.getMessage(), e.toString());
                logger.error("IO error applying {}: {}", describe(df), msg);
                failures.add(new FailedEdit(df, FailureReason.IO_ERROR, msg));
            }
        }

        return new ApplyResult(originals, failures);
    }

    private static void applyDelete(
            IContextManager cm,
            IConsoleIO io,
            LineEdit.DeleteFile df,
            Map<ProjectFile, String> originals,
            List<FailedEdit> failures)
            throws IOException {
        var pf = df.file();
        if (!pf.exists()) {
            failures.add(new FailedEdit(df, FailureReason.FILE_NOT_FOUND, "Delete: file does not exist"));
            return;
        }

        // capture original once
        originals.computeIfAbsent(pf, f -> {
            try {
                return f.read();
            } catch (IOException e) {
                return "";
            }
        });

        logger.info("Deleting {}", pf);
        Files.deleteIfExists(pf.absPath());
        try {
            cm.getRepo().remove(pf); // stage deletion (non-fatal on failure)
        } catch (Exception e) {
            logger.warn("Non-fatal: unable to stage deletion for {}: {}", pf, e.getMessage());
            io.systemOutput("Non-fatal: unable to stage deletion for " + pf);
        }
    }

    private static void applyEdit(
            LineEdit.EditFile ef,
            Map<ProjectFile, String> originals,
            List<FailedEdit> failures)
            throws IOException {
        var pf = ef.file();
        int begin = ef.beginLine();
        int end = ef.endLine();
        var body = ef.content();

        final boolean isInsertion = end < begin;

        final String original = pf.exists() ? pf.read() : "";
        var lines = splitIntoLines(original);
        int n = lines.size();

        // Capture original once, even if we end up creating the file via insertion
        originals.computeIfAbsent(pf, f -> original);

        if (isInsertion) {
            // '$' sentinel for insert maps to n+1; otherwise use provided begin
            int requestedBegin = (begin == Integer.MAX_VALUE) ? (n + 1) : begin;

            if (requestedBegin < 1 || requestedBegin > n + 1) {
                failures.add(new FailedEdit(
                        ef,
                        FailureReason.INVALID_LINE_RANGE,
                        "Invalid insertion index: begin=%d for file with %d lines (valid 1..%d)"
                                .formatted(begin, n, n + 1)));
                return;
            }

            var bodyLines = splitIntoLines(body);
            var newLines = new ArrayList<String>(n + bodyLines.size());
            int insertAt = requestedBegin - 1; // 0-based
            newLines.addAll(lines.subList(0, insertAt));
            newLines.addAll(bodyLines);
            newLines.addAll(lines.subList(insertAt, n));

            logger.info("Inserting {} lines at {} into {}", bodyLines.size(), requestedBegin, pf);
            writeBack(pf, newLines);
            return;
        }

        // Replacements/deletes require an existing file
        if (!pf.exists()) {
            failures.add(new FailedEdit(
                    ef,
                    FailureReason.FILE_NOT_FOUND,
                    "Replacement on non-existent file is not allowed. Use BRK_EDIT_EX <path> with `0 a` to create a new file."));
            return;
        }

        // Postel normalization for ranges: 0->1, $->n
        int normalizedBegin = (begin == 0) ? 1 : (begin == Integer.MAX_VALUE ? n : begin);
        int normalizedEnd   = (end   == Integer.MAX_VALUE) ? n : end;

        if (normalizedBegin < 1 || normalizedEnd < normalizedBegin || normalizedEnd > n) {
            failures.add(new FailedEdit(
                    ef,
                    FailureReason.INVALID_LINE_RANGE,
                    "Invalid replacement range: begin=%d end=%d for file with %d lines".formatted(begin, end, n)));
            return;
        }

        int startIdx = normalizedBegin - 1; // inclusive
        int endIdxExcl = normalizedEnd;     // exclusive

        var bodyLines = splitIntoLines(body);
        var newLines = new ArrayList<String>(n - (endIdxExcl - startIdx) + bodyLines.size());
        newLines.addAll(lines.subList(0, startIdx));
        newLines.addAll(bodyLines);
        newLines.addAll(lines.subList(endIdxExcl, n));

        logger.info("Replacing lines {}..{} (incl) in {} with {} new line(s)",
                    normalizedBegin, normalizedEnd, pf, bodyLines.size());
        writeBack(pf, newLines);
    }

    private static List<String> splitIntoLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        // Normalize: lines() discards trailing empty segment; ensure we don't duplicate trailing newline
        if (text.endsWith("\n")) {
            var content = text.substring(0, text.length() - 1);
            if (content.isEmpty()) {
                return List.of();
            }
            return content.lines().toList();
        }
        return text.lines().toList();
    }

    private static void writeBack(ProjectFile pf, List<String> lines) throws IOException {
        var content = String.join("\n", lines);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        pf.write(content);
    }

    private static String describe(LineEdit edit) {
        if (edit instanceof LineEdit.DeleteFile(ProjectFile file)) {
            return "Delete(" + file + ")";
        }
        if (edit instanceof LineEdit.EditFile ef) {
            return "Edit(" + ef.file() + "@" + ef.beginLine() + ".." + ef.endLine() + ")";
        }
        return edit.getClass().getSimpleName();
    }
}
