package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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
        ANCHOR_MISMATCH,
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

            // Validate position first (so tests expecting INVALID_LINE_RANGE still pass)
            if (requestedBegin < 1 || requestedBegin > n + 1) {
                failures.add(new FailedEdit(
                        ef,
                        FailureReason.INVALID_LINE_RANGE,
                        "Invalid insertion index: begin=%d for file with %d lines (valid 1..%d)"
                                .formatted(begin, n, n + 1)));
                return;
            }

            // Validate anchors (insertion validates only the begin anchor)
            String anchorError = validateAnchors(ef, lines);
            if (anchorError != null) {
                failures.add(new FailedEdit(ef, FailureReason.ANCHOR_MISMATCH, anchorError));
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

        // Validate range before anchors so tests expecting INVALID_LINE_RANGE still pass
        if (normalizedBegin < 1 || normalizedEnd < normalizedBegin || normalizedEnd > n) {
            failures.add(new FailedEdit(
                    ef,
                    FailureReason.INVALID_LINE_RANGE,
                    "Invalid replacement range: begin=%d end=%d for file with %d lines".formatted(begin, end, n)));
            return;
        }

        // Validate anchors: delete checks begin only; change checks begin+end
        String anchorError = validateAnchors(ef, lines);
        if (anchorError != null) {
            failures.add(new FailedEdit(ef, FailureReason.ANCHOR_MISMATCH, anchorError));
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

    private static @Nullable String validateAnchors(LineEdit.EditFile ef, List<String> lines) {
        final boolean isInsertion = ef.endLine() < ef.beginLine();
        final boolean isDelete = !isInsertion && ef.content().isEmpty();
        final boolean isChange = !isInsertion && !isDelete;
        final boolean isRange = !isInsertion && (ef.endLine() != ef.beginLine());

        var sb = new StringBuilder();
        boolean mismatch = false;

        // Always validate begin anchor (presence; content checked in checkOneAnchor)
        var beginAnchor = ef.beginAnchor();
        if (beginAnchor == null) {
            mismatch = true;
            sb.append("Anchor mismatch (begin): required anchor is missing\n");
        } else {
            var msg = checkOneAnchor("begin", beginAnchor, lines);
            if (msg != null) {
                mismatch = true;
                sb.append(msg).append('\n');
            }
        }

        if (isChange || (isDelete && isRange)) {
            var endAnchor = ef.endAnchor();
            if (endAnchor == null) {
                mismatch = true;
                sb.append("Anchor mismatch (end): required anchor is missing\n");
            } else {
                var msg2 = checkOneAnchor("end", endAnchor, lines);
                if (msg2 != null) {
                    mismatch = true;
                    sb.append(msg2).append('\n');
                }
            }
        }

        return mismatch ? sb.toString().trim() : null;
    }

    private static @Nullable String checkOneAnchor(String which, LineEdit.Anchor anchor, List<String> lines) {
        var token = anchor.addrToken();

        // Always skip validation for 0 / $
        if ("0".equals(token) || "$".equals(token)) {
            return null;
        }

        var expectedRaw = anchor.content();
        var expected = expectedRaw.strip();

        var actualOpt = contentForToken(lines, token);
        var actualRaw = actualOpt.orElse("");
        var actual = actualRaw.strip();

        // Empty file + blank expected is OK
        if (!actualOpt.isPresent() && expected.isEmpty()) {
            return null;
        }
        if (!actual.equals(expected)) {
            return "Anchor mismatch (" + which + "): token '" + token
                    + "' expected [" + expectedRaw + "] but was [" + (actualOpt.isPresent() ? actualRaw : "<no line>") + "]";
        }
        return null;
    }

    /** Returns the exact line content for a token ("0", "1".., "$"), or empty if no such line exists. */
    private static Optional<String> contentForToken(List<String> lines, String token) {
        int n = lines.size();
        if ("$".equals(token)) {
            return (n == 0) ? java.util.Optional.empty() : java.util.Optional.of(lines.get(n - 1));
        }
        if ("0".equals(token)) {
            return (n == 0) ? java.util.Optional.empty() : java.util.Optional.of(lines.get(0));
        }
        try {
            int idx = Integer.parseInt(token);
            if (idx >= 1 && idx <= n) {
                return java.util.Optional.of(lines.get(idx - 1));
            }
        } catch (NumberFormatException ignore) {
            // not possible given parser constraints
        }
        return java.util.Optional.empty();
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
