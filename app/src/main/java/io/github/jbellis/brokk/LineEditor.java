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

    public enum ApplyFailureReason {
        FILE_NOT_FOUND,
        INVALID_LINE_RANGE,
        ANCHOR_MISMATCH,
        OVERLAPPING_EDITS,
        IO_ERROR
    }

    public record ApplyFailure(LineEdit edit, ApplyFailureReason reason, String commentary) {}

    public record ApplyResult(Map<ProjectFile, String> originalContents, List<ApplyFailure> failures) {
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
        var failures = new ArrayList<ApplyFailure>();

        // Partition edits: group EditFile per path; collect DeleteFile preserving original order.
        var editsByPath = new HashMap<String, List<LineEdit.EditFile>>();
        var deletes = new ArrayList<LineEdit.DeleteFile>();

        for (var edit : edits) {
            if (edit instanceof LineEdit.EditFile ef) {
                editsByPath.computeIfAbsent(ef.file(), k -> new ArrayList<>()).add(ef);
            } else if (edit instanceof LineEdit.DeleteFile df) {
                deletes.add(df);
            } else {
                failures.add(new ApplyFailure(
                        edit, ApplyFailureReason.IO_ERROR, "Unknown edit type: " + edit.getClass().getSimpleName()));
            }
        }

        // Apply per path:
        //   1) replacements/deletes (begin <= end) sorted descending (bottom-to-top)
        //   2) insertions (end < begin) in original input order
        for (var entry : editsByPath.entrySet()) {
            var perFile = entry.getValue();

            // Detect overlapping edits for this file; record failures and skip them
            var overlapping = detectOverlapping(perFile);
            if (!overlapping.isEmpty()) {
                for (var ef : overlapping) {
                    failures.add(new ApplyFailure(
                            ef,
                            ApplyFailureReason.OVERLAPPING_EDITS,
                            "Overlapping edit detected within " + ef.file() + ": " + formatOverlapBounds(ef)));
                }
            }

            var changes = new ArrayList<LineEdit.EditFile>();
            var inserts = new ArrayList<LineEdit.EditFile>();

            for (var ef : perFile) {
                if (overlapping.contains(ef)) continue; // skip conflicted edits
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
                    applyEdit(cm, ef, originals, failures);
                } catch (IOException e) {
                    var msg = java.util.Objects.requireNonNullElse(e.getMessage(), e.toString());
                    logger.error("IO error applying {}: {}", describe(ef), msg);
                    failures.add(new ApplyFailure(ef, ApplyFailureReason.IO_ERROR, msg));
                }
            }

            // 2) Apply insertions in original order
            for (var ef : inserts) {
                try {
                    applyEdit(cm, ef, originals, failures);
                } catch (IOException e) {
                    var msg = java.util.Objects.requireNonNullElse(e.getMessage(), e.toString());
                    logger.error("IO error applying {}: {}", describe(ef), msg);
                    failures.add(new ApplyFailure(ef, ApplyFailureReason.IO_ERROR, msg));
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
                failures.add(new ApplyFailure(df, ApplyFailureReason.IO_ERROR, msg));
            }
        }

        return new ApplyResult(originals, failures);
    }

    private static void applyDelete(
            IContextManager cm,
            IConsoleIO io,
            LineEdit.DeleteFile df,
            Map<ProjectFile, String> originals,
            List<ApplyFailure> failures)
            throws IOException {
        var pf = cm.toFile(df.file());
        if (!pf.exists()) {
            failures.add(new ApplyFailure(df, ApplyFailureReason.FILE_NOT_FOUND, "Delete: file does not exist"));
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
        java.nio.file.Files.deleteIfExists(pf.absPath());
        try {
            cm.getRepo().remove(pf); // stage deletion (non-fatal on failure)
        } catch (Exception e) {
            logger.warn("Non-fatal: unable to stage deletion for {}: {}", pf, e.getMessage());
            io.systemOutput("Non-fatal: unable to stage deletion for " + pf);
        }
    }

    private static void applyEdit(
            IContextManager cm,
            LineEdit.EditFile ef,
            Map<ProjectFile, String> originals,
            List<ApplyFailure> failures)
            throws IOException {
        var pf = cm.toFile(ef.file());
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
                failures.add(new ApplyFailure(
                        ef,
                        ApplyFailureReason.INVALID_LINE_RANGE,
                        "Invalid insertion index: begin=%d for file with %d lines (valid 1..%d)"
                                .formatted(begin, n, n + 1)));
                return;
            }

            // Validate anchors (insertion validates only the begin anchor), correcting off-by-1 addresses
            try {
                ef = validateAnchors(ef, lines);
            } catch (Abort e) {
                failures.add(new ApplyFailure(ef, ApplyFailureReason.ANCHOR_MISMATCH, java.util.Objects.requireNonNullElse(e.getMessage(), "Anchor mismatch")));
                return;
            }

            var bodyLines = splitIntoLines(body);
            var newLines = new ArrayList<String>(n + bodyLines.size());

            // Map corrected anchor to insertion index (1-based); '$' sentinel maps to end+1
            int requestedBeginCorrected = (ef.beginLine() == Integer.MAX_VALUE) ? (n + 1) : ef.beginLine();
            int insertAt = requestedBeginCorrected - 1; // 0-based

            newLines.addAll(lines.subList(0, insertAt));
            newLines.addAll(bodyLines);
            newLines.addAll(lines.subList(insertAt, n));

            logger.info("Inserting {} lines at {} into {}", bodyLines.size(), requestedBeginCorrected, pf);
            writeBack(pf, newLines);
            return;
        }

        // Replacements/deletes require an existing file
        if (!pf.exists()) {
            failures.add(new ApplyFailure(
                    ef,
                    ApplyFailureReason.FILE_NOT_FOUND,
                    "Replacement on non-existent file is not allowed. Use BRK_EDIT_EX <path> with `0 a` to create a new file."));
            return;
        }

        // Postel normalization for ranges: 0->1, $->n
        int normalizedBegin = (begin == 0) ? 1 : (begin == Integer.MAX_VALUE ? n : begin);
        int normalizedEnd   = (end   == Integer.MAX_VALUE) ? n : end;

        // Validate range before anchors so tests expecting INVALID_LINE_RANGE still pass
        if (normalizedBegin < 1 || normalizedEnd < normalizedBegin || normalizedEnd > n) {
            failures.add(new ApplyFailure(
                    ef,
                    ApplyFailureReason.INVALID_LINE_RANGE,
                    "Invalid replacement range: begin=%d end=%d for file with %d lines".formatted(begin, end, n)));
            return;
        }

        // Validate anchors: delete checks begin only; change checks begin+end (with off-by-1 correction)
        try {
            ef = validateAnchors(ef, lines);
        } catch (Abort e) {
            failures.add(new ApplyFailure(ef, ApplyFailureReason.ANCHOR_MISMATCH, java.util.Objects.requireNonNullElse(e.getMessage(), "Anchor mismatch")));
            return;
        }

        // Recompute normalized bounds from corrected anchors (Postel correction)
        begin = ef.beginLine();
        end = ef.endLine();
        int correctedBegin = (begin == 0) ? 1 : (begin == Integer.MAX_VALUE ? n : begin);
        int correctedEnd   = (end   == Integer.MAX_VALUE) ? n : end;

        int startIdx = correctedBegin - 1; // inclusive
        int endIdxExcl = correctedEnd;     // exclusive

        var bodyLines = splitIntoLines(body);
        var newLines = new ArrayList<String>(n - (endIdxExcl - startIdx) + bodyLines.size());
        newLines.addAll(lines.subList(0, startIdx));
        newLines.addAll(bodyLines);
        newLines.addAll(lines.subList(endIdxExcl, n));

        logger.info("Replacing lines {}..{} (incl) in {} with {} new line(s)",
                    correctedBegin, correctedEnd, pf, bodyLines.size());
        writeBack(pf, newLines);
    }

    // Unchecked control flow for validation failures
    private static final class Abort extends RuntimeException {
        Abort(String message) {
            super(message);
        }
    }

    // Returns a corrected edit (possibly with adjusted begin/end line numbers and anchors) or throws Abort on failure.
    private static LineEdit.EditFile validateAnchors(LineEdit.EditFile ef, List<String> lines) {
        final boolean isInsertion = ef.endLine() < ef.beginLine();
        final boolean isDelete = !isInsertion && ef.content().isEmpty();
        final boolean isChange = !isInsertion && !isDelete;
        final boolean isRange = !isInsertion && (ef.endLine() != ef.beginLine());

        // Validate and possibly correct anchors
        var correctedBeginAnchor = checkOneAnchor("begin", ef, ef.beginAnchor(), lines);

        LineEdit.Anchor correctedEndAnchor = null;
        if (isChange || (isDelete && isRange)) {
            var endAnchor = ef.endAnchor();
            if (endAnchor == null) {
                throw new Abort("Anchor mismatch (end): required anchor is missing");
            }
            correctedEndAnchor = checkOneAnchor("end", ef, endAnchor, lines);
        } else if (ef.endAnchor() != null) {
            // For single-line delete or insertion, ignore endAnchor if present
            correctedEndAnchor = ef.endAnchor();
        }

        // Compute corrected begin and end lines from anchors
        int n = lines.size();

        int correctedBeginLine;
        int correctedEndLine;

        if (isInsertion) {
            // Insertion: begin is k+1 (or sentinel for $)
            if (ef.beginLine() == Integer.MAX_VALUE) {
                correctedBeginLine = Integer.MAX_VALUE;
                correctedEndLine = Integer.MAX_VALUE - 1;
            } else {
                var addr = correctedBeginAnchor.address();
                if ("0".equals(addr)) {
                    correctedBeginLine = 1;
                    correctedEndLine = 0;
                } else if ("$".equals(addr)) {
                    correctedBeginLine = Integer.MAX_VALUE;
                    correctedEndLine = Integer.MAX_VALUE - 1;
                } else {
                    int k = Integer.parseInt(addr);
                    correctedBeginLine = k + 1;
                    correctedEndLine = correctedBeginLine - 1;
                }
            }
        } else {
            // Change/Delete range mapping: 0 -> 1, $ -> n
            var bAddr = correctedBeginAnchor.address();
            correctedBeginLine =
                    "0".equals(bAddr) ? 1 :
                    "$".equals(bAddr) ? n :
                    Integer.parseInt(bAddr);

            if (isRange) {
                var eAddr = correctedEndAnchor == null ? bAddr : correctedEndAnchor.address();
                correctedEndLine =
                        "0".equals(eAddr) ? 1 :
                        "$".equals(eAddr) ? n :
                        Integer.parseInt(eAddr);
            } else {
                correctedEndLine = correctedBeginLine;
            }
        }

        // Build corrected edit (anchors reflect any corrected addresses)
        return new LineEdit.EditFile(
                ef.file(),
                correctedBeginLine,
                correctedEndLine,
                ef.content(),
                correctedBeginAnchor,
                correctedEndAnchor
        );
    }

    // Validates a single anchor and returns a possibly corrected anchor; throws Abort on failure.
    private static LineEdit.Anchor checkOneAnchor(String which, LineEdit.EditFile ef, LineEdit.Anchor anchor, List<String> lines) {
        var address = anchor.address();

        // Always skip validation for 0
        if ("0".equals(address)) {
            return anchor;
        }

        final boolean editSideIsDollar =
                ("begin".equals(which) && ef.beginLine() == Integer.MAX_VALUE)
                        || ("end".equals(which) && ef.endLine() == Integer.MAX_VALUE);

        // For edits addressed at '$', allow numeric anchors within ±2 of EOF; compare content to last line.
        if (editSideIsDollar && !"$".equals(address)) {
            try {
                int k = Integer.parseInt(address);
                int n = lines.size();
                if (Math.abs(k - n) <= 2) {
                    var expectedRaw = anchor.content();
                    var expected = expectedRaw.strip();

                    var actualOpt = contentForAddress(lines, "$");
                    var actualRaw = actualOpt.orElse("");
                    var actual = actualRaw.strip();

                    if (!actualOpt.isPresent() && expected.isEmpty()) {
                        return anchor;
                    }
                    if (!actual.equals(expected)) {
                        throw new Abort("Anchor mismatch (" + which + ") for `$` expected `" + expectedRaw + "` but was `" + (actualOpt.isPresent() ? actualRaw : "<no line>") + "`");
                    }
                    return anchor; // success within tolerance and content matched
                } else {
                    throw new Abort("Anchor mismatch (" + which + "): specified address `$` but line " + address
                            + " is too far from end-of-file (n=" + n + "). You can just use `$` as the anchor address directly.");
                }
            } catch (NumberFormatException ignore) {
                // fall through to normal handling
            }
        }

        // If the address is '$', accept without requiring content match
        if ("$".equals(address)) {
            return anchor;
        }

        var expectedRaw = anchor.content();
        var expected = expectedRaw.strip();

        var actualOpt = contentForAddress(lines, address);
        var actualRaw = actualOpt.orElse("");
        var actual = actualRaw.strip();

        // Empty file + blank expected is OK
        if (actualOpt.isEmpty() && expected.isEmpty()) {
            return anchor;
        }
        if (!actual.equals(expected)) {
            try {
                int k = Integer.parseInt(address);
                int n = lines.size();
                boolean prevOk = (k - 1 >= 1) && lines.get(k - 2).strip().equals(expected);
                boolean nextOk = (k + 1 <= n) && lines.get(k).strip().equals(expected);
                if (prevOk ^ nextOk) {
                    int corrected = prevOk ? k - 1 : k + 1;
                    logger.info("Anchor {} address corrected by ±1: {} -> {} based on matching content", which, k, corrected);
                    return new LineEdit.Anchor(Integer.toString(corrected), anchor.content());
                }
            } catch (NumberFormatException ignore) {
                // non-numeric addresses are handled above; fall through to diagnostics
            }

            // Enhanced diagnostics
            String suggestion1 = null;
            try {
                int given = Integer.parseInt(address);
                int n = lines.size();
                int givenIdx0 = given - 1;

                int bestIdx = -1;
                for (int radius = 0; radius <= n; radius++) {
                    int left = givenIdx0 - radius;
                    int right = givenIdx0 + radius;
                    boolean found = false;
                    if (left >= 0) {
                        var lraw = lines.get(left);
                        if (lraw.strip().equals(expected)) {
                            bestIdx = left;
                            found = true;
                        }
                    }
                    if (!found && right < n) {
                        var rraw = lines.get(right);
                        if (rraw.strip().equals(expected)) {
                            bestIdx = right;
                            found = true;
                        }
                    }
                    if (found) break;
                }
                if (bestIdx >= 0) {
                    suggestion1 = "@" + (bestIdx + 1) + "| " + expectedRaw;
                }
            } catch (NumberFormatException ignore) {
                // leave suggestion1 as null
            }

            String actualGiven = "@" + address + "| " + (actualOpt.isPresent() ? actualRaw : "");
            if (suggestion1 != null) {
                throw new Abort("""
                        Anchor mismatch (%s)!
                        You gave
                        @%s| %s
                        which is not a valid line/content pairing. You may have meant
                        %s
                        or perhaps
                        %s
                        NOTE: line numbers may have shifted due to other edits, verify them against the latest contents above!"""
                        .formatted(which, address, expectedRaw, suggestion1, actualGiven).trim());
            } else {
                throw new Abort("""
                        Anchor mismatch (%s)!
                        You gave
                        @%s| %s
                        which is not a valid line/content pairing. I could not find that content near the cited line.
                        For reference, the cited line is
                        %s
                        NOTE: line numbers may have shifted due to other edits, verify them against the latest contents above!"""
                        .formatted(which, address, expectedRaw, actualGiven).trim());
            }
        }
        return anchor;
    }

    /** Returns the exact line content for an address ("0", "1".., "$"), or empty if no such line exists. */
    private static Optional<String> contentForAddress(List<String> lines, String address) {
        int n = lines.size();
        if ("$".equals(address)) {
            return (n == 0) ? java.util.Optional.empty() : java.util.Optional.of(lines.get(n - 1));
        }
        if ("0".equals(address)) {
            return (n == 0) ? java.util.Optional.empty() : java.util.Optional.of(lines.get(0));
        }
        try {
            int idx = Integer.parseInt(address);
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
        if (edit instanceof LineEdit.DeleteFile(String path)) {
            return "Delete(" + path + ")";
        }
        if (edit instanceof LineEdit.EditFile ef) {
            return "Edit(" + ef.file() + "@" + ef.beginLine() + ".." + ef.endLine() + ")";
        }
        return edit.getClass().getSimpleName();
    }

    /**
     * Formats a user-friendly description of the edit bounds specifically for overlap reporting.
     * - Ranges display as "range B..E" with '$' for end-of-file
     * - Insertions display as one of:
     *     "insert at start-of-file (before line 1)"
     *     "insert after line K"
     *     "append at end-of-file ($)"
     */
    private static String formatOverlapBounds(LineEdit.EditFile ef) {
        if (isInsertion(ef)) {
            // insertion occurs between (k-1) and k; begin is the k index (1-based)
            int k = ef.beginLine();
            if (k == Integer.MAX_VALUE) {
                return "append at end-of-file ($)";
            }
            if (k == 1) {
                return "insert at start-of-file (before line 1)";
            }
            return "insert after line " + (k - 1);
        } else {
            String b = ef.beginLine() == Integer.MAX_VALUE ? "$" : Integer.toString(ef.beginLine());
            String e = ef.endLine() == Integer.MAX_VALUE ? "$" : Integer.toString(ef.endLine());
            return "range " + b + ".." + e;
        }
    }

    // Detect overlapping edits within a single file. Returns the set of edits that conflict.
    private static List<LineEdit.EditFile> detectOverlapping(List<LineEdit.EditFile> edits) {
        var conflicts = new java.util.HashSet<LineEdit.EditFile>();
        int size = edits.size();
        for (int i = 0; i < size; i++) {
            var a = edits.get(i);
            for (int j = i + 1; j < size; j++) {
                var b = edits.get(j);
                if (overlaps(a, b)) {
                    conflicts.add(a);
                    conflicts.add(b);
                }
            }
        }
        if (conflicts.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(conflicts);
    }

    private static boolean isInsertion(LineEdit.EditFile ef) {
        return ef.endLine() < ef.beginLine();
    }

    private static boolean overlaps(LineEdit.EditFile a, LineEdit.EditFile b) {
        boolean aIns = isInsertion(a);
        boolean bIns = isInsertion(b);

        if (!aIns && !bIns) {
            // range-vs-range: [a.begin..a.end] intersects [b.begin..b.end]
            int lo = Math.max(a.beginLine(), b.beginLine());
            int hi = Math.min(a.endLine(), b.endLine());
            return lo <= hi;
        }

        if (aIns && bIns) {
            // Allow multiple insertions at the same location; not considered overlapping.
            return false;
        }

        // insertion vs range
        var ins = aIns ? a : b;
        var rng = aIns ? b : a;

        // '$ a' is modeled as begin=Integer.MAX_VALUE (insert after last line); do not consider it overlapping
        if (ins.beginLine() == Integer.MAX_VALUE) {
            return false;
        }

        int k = ins.beginLine(); // insertion occurs between (k-1) and k
        int rBegin = rng.beginLine();
        int rEnd = rng.endLine(); // rng is guaranteed to be range (end >= begin)

        // insertion overlaps the range if it falls strictly inside the range boundary:
        // specifically, inserting before the first replaced line (k == rBegin) does NOT overlap,
        // but inserting at any position within the replaced lines (rBegin < k <= rEnd) does.
        return (rBegin < k) && (k <= rEnd);
    }
}
