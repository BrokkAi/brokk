package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Apply "apply_patch" operations to the workspace.
 *
 * Chunk semantics:
 *   anchor (from "@@"), preContext, oldLines, newLines, postContext, isEndOfFile
 * Matching replaces only the OLD segment inside PRE + OLD + POST, preserving PRE/POST.
 */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

    private EditBlock() {
        // utility class
    }

    /**
     * Helper that returns the first code block found between triple backticks. Skips any text on the same line as the
     * opening backticks (like language specifiers) and starts capturing from the next line. Returns an empty string if
     * no valid block is found.
     */
    public static String extractCodeFromTripleBackticks(String text) {
        // Pattern: ``` followed by optional non-newline chars, then newline, then capture until ```
        // The (.*) is greedy to ensure embedded ``` within the block are treated as content.
        var matcher = Pattern.compile(
                        "```[^\\n]*\\n(.*)```", // Skips language specifier line; (.*) captures content greedily.
                        Pattern.DOTALL)
                .matcher(text);

        if (matcher.find()) {
            // group(1) captures the content between the initial newline (after ```[lang]) and the closing ```
            return matcher.group(1);
        }
        return "";
    }

    // ---------------- Sealed operations ----------------

    public sealed interface FileOperation permits AddFile, DeleteFile, UpdateFile {
        String rawPath();
    }

    public record AddFile(String rawPath, String contents) implements FileOperation {}
    public record DeleteFile(String rawPath) implements FileOperation {}
    public record UpdateFile(String rawPath, @Nullable String moveTo, List<UpdateFileChunk> chunks)
            implements FileOperation {}

    public record UpdateFileChunk(
            @Nullable String anchor,
            List<String> preContext,
            List<String> oldLines,
            List<String> newLines,
            List<String> postContext,
            boolean isEndOfFile) {}

    public enum ParseFailureReason { FILE_NOT_FOUND, NO_MATCH, AMBIGUOUS_MATCH, IO_ERROR }

    public record EditResult(Map<ProjectFile, String> originalContents, List<FailedBlock> failedBlocks) {
        public boolean hadSuccessfulEdits() { return !originalContents.isEmpty(); }
    }

    public record FailedBlock(FileOperation block, ParseFailureReason reason, String commentary) {
        public FailedBlock(FileOperation block, ParseFailureReason reason) { this(block, reason, ""); }
    }

    // ---------------- Exceptions (matching) ----------------

    public static class NoMatchException extends Exception { public NoMatchException(String msg) { super(msg); } }
    public static class AmbiguousMatchException extends Exception { public AmbiguousMatchException(String m) { super(m); } }

    public static sealed class SymbolResolutionException extends Exception { public SymbolResolutionException(String m) { super(m); } }
    public static final class SymbolNotFoundException extends SymbolResolutionException { public SymbolNotFoundException(String m) { super(m); } }
    public static final class SymbolInvalidException extends SymbolResolutionException { public SymbolInvalidException(String m) { super(m); } }
    public static final class SymbolAmbiguousException extends SymbolResolutionException { public SymbolAmbiguousException(String m) { super(m); } }

    // ---------------- Public API ----------------

    public static EditResult applyEditBlocks(IContextManager contextManager, IConsoleIO io, Collection<FileOperation> ops)
            throws IOException {
        final var failed = new ArrayList<FailedBlock>();
        final var originals = new HashMap<ProjectFile, String>();
        final var newFiles = new ArrayList<ProjectFile>();
        IGitRepo repo = contextManager.getRepo();

        for (var op : ops) {
            try {
                switch (op) {
                    case AddFile add -> {
                        var pf = toProjectFile(contextManager, add.rawPath());
                        var had = pf.exists();
                        var orig = had ? pf.read() : "";
                        pf.write(ensureTrailingNewline(add.contents()));
                        if (!had) newFiles.add(pf);
                        originals.putIfAbsent(pf, orig);
                        logger.info("Added/updated {}", pf);
                    }
                    case DeleteFile del -> {
                        var pf = toProjectFile(contextManager, del.rawPath());
                        if (!pf.exists()) {
                            failed.add(new FailedBlock(del, ParseFailureReason.FILE_NOT_FOUND));
                            logger.warn("Delete target missing: {}", pf);
                            continue;
                        }
                        var orig = pf.read();
                        java.nio.file.Files.deleteIfExists(pf.absPath());
                        repo.remove(pf);
                        originals.putIfAbsent(pf, orig);
                        logger.info("Deleted {}", pf);
                    }
                    case UpdateFile upd -> {
                        var src = toProjectFile(contextManager, upd.rawPath());
                        if (!src.exists()) {
                            failed.add(new FailedBlock(upd, ParseFailureReason.FILE_NOT_FOUND));
                            logger.warn("Update target missing: {}", src);
                            continue;
                        }
                        var original = src.read();
                        String revised;
                        try {
                            revised = applyChunks(original, upd.chunks(), src.toString());
                        } catch (NoMatchException e) {
                            failed.add(new FailedBlock(upd, ParseFailureReason.NO_MATCH, requireNonNull(e.getMessage())));
                            continue;
                        } catch (AmbiguousMatchException e) {
                            failed.add(new FailedBlock(upd, ParseFailureReason.AMBIGUOUS_MATCH, requireNonNull(e.getMessage())));
                            continue;
                        }
                        if (upd.moveTo() != null && !upd.moveTo().isBlank()) {
                            var dest = toProjectFile(contextManager, upd.moveTo());
                            dest.write(revised);
                            java.nio.file.Files.deleteIfExists(src.absPath());
                            originals.putIfAbsent(dest, original);
                            newFiles.add(dest);
                        } else {
                            src.write(revised);
                            originals.putIfAbsent(src, original);
                        }
                        logger.info("Updated {}", src);
                    }
                }
            } catch (IOException ioe) {
                failed.add(new FailedBlock(op, ParseFailureReason.IO_ERROR, requireNonNull(ioe.getMessage())));
            } catch (SymbolInvalidException iae) {
                failed.add(new FailedBlock(op, ParseFailureReason.FILE_NOT_FOUND, requireNonNull(iae.getMessage())));
            } catch (GitAPIException e) {
                // ignore
            }
        }

        if (!newFiles.isEmpty()) {
            try {
                repo.add(newFiles);
                repo.invalidateCaches();
            } catch (GitAPIException e) {
                io.toolError("Failed to add " + newFiles + " to git: " + e.getMessage(), "Error");
            }
            contextManager.editFiles(newFiles);
        }

        return new EditResult(originals, failed);
    }

    // ---------------- Chunk application ----------------

    private static String applyChunks(String original, List<UpdateFileChunk> chunks, String path)
            throws NoMatchException, AmbiguousMatchException {
        var lines = splitLogical(original);

        record Replacement(int start, int oldLen, List<String> newSeg) {}
        var replacements = new ArrayList<Replacement>();
        int cursor = 0;

        for (var ch : chunks) {
            if (ch.anchor() != null) {
                var hit = findUnique(lines, List.of(ch.anchor()), cursor, false);
                if (hit.isEmpty()) throw new NoMatchException("Context anchor not found in " + path + ": " + ch.anchor());
                cursor = hit.getAsInt() + 1;
            }

            // Build match pattern: pre + old + post (old may be empty for pure insert)
            var pattern = new ArrayList<String>(ch.preContext().size() + ch.oldLines().size() + ch.postContext().size());
            pattern.addAll(ch.preContext());
            pattern.addAll(ch.oldLines());
            pattern.addAll(ch.postContext());

            if (ch.oldLines().isEmpty()) {
                // pure insertion: find the surrounding pre+post window; if both are empty and EOF is marked, append
                var match = findUnique(lines, pattern, cursor, ch.isEndOfFile());
                if (match.isEmpty()) throw new NoMatchException("Failed to locate insertion site in " + path);
                int insertion = match.getAsInt() + ch.preContext().size();
                replacements.add(new Replacement(insertion, 0, ch.newLines()));
                cursor = insertion + ch.newLines().size();
                continue;
            }

            var m = findUnique(lines, pattern, cursor, ch.isEndOfFile());
            if (m.isEmpty()) throw new NoMatchException("Failed to find expected lines in " + path);
            int start = m.getAsInt() + ch.preContext().size();
            int oldLen = ch.oldLines().size();
            replacements.add(new Replacement(start, oldLen, ch.newLines()));
            cursor = start + oldLen;
        }

        // apply in reverse
        var out = new ArrayList<>(lines);
        replacements.sort(Comparator.comparingInt(r -> -r.start));
        for (var r : replacements) {
            for (int k = 0; k < r.oldLen; k++) if (r.start < out.size()) out.remove(r.start);
            out.addAll(r.start, r.newSeg);
        }
        return joinWithFinalNewline(out);
    }

    private static List<String> splitLogical(String text) {
        if (text.isEmpty()) return new ArrayList<>();
        var arr = text.split("\n", -1);
        var list = new ArrayList<String>(arr.length);
        for (int i = 0; i < arr.length; i++) {
            if (i == arr.length - 1 && arr[i].isEmpty()) break; // drop trailing sentinel
            list.add(arr[i]);
        }
        return list;
    }

    private static String joinWithFinalNewline(List<String> lines) {
        var s = String.join("\n", lines);
        return s.endsWith("\n") ? s : s + "\n";
    }

    private static String ensureTrailingNewline(String s) {
        return s.isEmpty() || s.endsWith("\n") ? s : s + "\n";
    }

    // ---------------- Matching (with ambiguity detection) ----------------

    private static OptionalInt findUnique(List<String> lines, List<String> pattern, int start, boolean eof)
            throws AmbiguousMatchException {
        // Empty pattern: treat as a cursor or EOF insertion point
        if (pattern.isEmpty()) {
            int idx = eof ? lines.size() : Math.max(0, Math.min(start, lines.size()));
            return OptionalInt.of(idx);
        }
        if (pattern.size() > lines.size()) return OptionalInt.empty();

        int searchStart = eof ? lines.size() - pattern.size() : start;
        int last = Math.min(lines.size() - pattern.size(), lines.size() - pattern.size());

        // pass 1: exact
        var m = findMatches(lines, pattern, searchStart, last, Mode.EXACT);
        if (m.size() > 1) throw new AmbiguousMatchException("Multiple exact matches found");
        if (m.size() == 1) return OptionalInt.of(m.getFirst());
        // pass 2: rstrip
        m = findMatches(lines, pattern, searchStart, last, Mode.RSTRIP);
        if (m.size() > 1) throw new AmbiguousMatchException("Multiple trailing-whitespace-insensitive matches found");
        if (m.size() == 1) return OptionalInt.of(m.getFirst());
        // pass 3: trim
        m = findMatches(lines, pattern, searchStart, last, Mode.TRIM);
        if (m.size() > 1) throw new AmbiguousMatchException("Multiple whitespace-insensitive matches found");
        if (m.size() == 1) return OptionalInt.of(m.getFirst());
        // pass 4: normalized punctuation + odd spaces
        m = findMatches(lines, pattern, searchStart, last, Mode.NORMALIZED);
        if (m.size() > 1) throw new AmbiguousMatchException("Multiple normalized matches found");
        if (m.size() == 1) return OptionalInt.of(m.getFirst());
        return OptionalInt.empty();
    }

    private enum Mode { EXACT, RSTRIP, TRIM, NORMALIZED }

    private static List<Integer> findMatches(List<String> lines, List<String> pattern, int start, int last, Mode mode) {
        if (start < 0) start = 0;
        if (last < 0) return List.of();
        last = Math.min(last, lines.size() - pattern.size());
        var hits = new ArrayList<Integer>();
        for (int i = start; i <= last; i++) {
            boolean ok = true;
            for (int k = 0; k < pattern.size(); k++) {
                if (!eq(lines.get(i + k), pattern.get(k), mode)) { ok = false; break; }
            }
            if (ok) hits.add(i);
        }
        return hits;
    }

    private static boolean eq(String a, String b, Mode mode) {
        return switch (mode) {
            case EXACT -> Objects.equals(a, b);
            case RSTRIP -> Objects.equals(rstrip(a), rstrip(b));
            case TRIM -> Objects.equals(a.trim(), b.trim());
            case NORMALIZED -> Objects.equals(normalize(a), normalize(b));
        };
    }

    private static String rstrip(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return s.substring(0, i + 1);
    }

    private static String normalize(String s) {
        var t = Normalizer.normalize(s, Normalizer.Form.NFC).trim();
        t = t.replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2010', '-').replace('\u2011', '-')
                .replace('\u2012', '-').replace('\u2013', '-')
                .replace('\u2014', '-').replace('\u2212', '-')
                .replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u2009', ' ');
        return t.replaceAll(" {2,}", " ");
    }

    // ---------------- Path resolution ----------------

    private static ProjectFile toProjectFile(IContextManager cm, String path) throws SymbolInvalidException {
        try { return cm.toFile(requireNonNull(path)); }
        catch (IllegalArgumentException e) { throw new SymbolInvalidException("Invalid path: " + path); }
    }
}
