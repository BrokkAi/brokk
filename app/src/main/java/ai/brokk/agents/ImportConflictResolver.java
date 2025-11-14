package ai.brokk.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves import-only conflict hunks (imports + blank lines, no comments).
 *
 * <p>Detection: a hunk is considered an import conflict only if both {@code ours} and {@code theirs} consist
 * exclusively of Java {@code import} statements and blank lines. Comments or any other code make it ineligible.
 *
 * <p>Resolution: between {@code ours} and {@code theirs}:
 *
 * <ul>
 *   <li>If one side is a supersequence of the other (in order, not necessarily contiguous), return that side verbatim.
 *   <li>Otherwise, return the sorted union of imports, preserving at most a single leading and a single trailing blank
 *       line if present on either side. Interior blank lines are dropped.
 * </ul>
 */
public final class ImportConflictResolver {

    /** Matches a single-line Java import (including static and wildcard). */
    private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.$*]+\\s*;\\s*$");

    private ImportConflictResolver() {}

    /** Returns {@code true} if {@code line} is either a Java import statement or a blank line. */
    private static boolean isImportOrBlankLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return true;
        return IMPORT_LINE.matcher(line).matches();
    }

    /**
     * Returns {@code true} if every line in {@code lines} is an import statement or blank. Any comments or other code
     * cause this to return {@code false}.
     */
    public static boolean isImportBlankBlock(List<String> lines) {
        for (String line : lines) {
            if (!isImportOrBlankLine(line)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines whether a conflict hunk is eligible for this resolver. Only {@code ours} and {@code theirs} are
     * considered; {@code base} may be ignored.
     *
     * @param base the base lines (may be {@code null}; ignored for eligibility)
     * @param ours the "ours" side lines
     * @param theirs the "theirs" side lines
     * @return {@code true} iff both sides are import-or-blank blocks
     */
    public static boolean isImportConflict(@Nullable List<String> base, List<String> ours, List<String> theirs) {
        return isImportBlankBlock(ours) && isImportBlankBlock(theirs);
    }

    /**
     * Resolves an import-only conflict. Both inputs must be import-or-blank blocks.
     *
     * <p>Strategy:
     *
     * <ol>
     *   <li>If one side is a supersequence of the other, return that side verbatim.
     *   <li>Else, return the sorted union of import lines. Preserve at most a single leading and trailing blank line if
     *       present on either side.
     * </ol>
     *
     * @param ours the "ours" lines (imports + blanks only)
     * @param theirs the "theirs" lines (imports + blanks only)
     * @return merged lines for the conflict hunk
     * @throws IllegalArgumentException if either side contains comments/other code
     */
    public static List<String> resolveImportConflict(List<String> ours, List<String> theirs) {
        if (!isImportBlankBlock(ours) || !isImportBlankBlock(theirs)) {
            throw new IllegalArgumentException("Not an import-only hunk (comments or code present).");
        }

        if (ours.equals(theirs)) {
            return ours;
        }
        if (ours.size() >= theirs.size() && (theirs.isEmpty() || isSupersequence(ours, theirs))) {
            return ours;
        }
        if (theirs.size() >= ours.size() && (ours.isEmpty() || isSupersequence(theirs, ours))) {
            return theirs;
        }

        return mergeImportsAndSpaces(ours, theirs);
    }

    /**
     * Merges two import-or-blank regions: sorted union of imports; at most one leading and one trailing blank line
     * retained.
     */
    private static List<String> mergeImportsAndSpaces(List<String> a, List<String> b) {
        String leadingBlank = null;
        if (!a.isEmpty() && a.get(0).trim().isEmpty()) leadingBlank = a.get(0);
        else if (!b.isEmpty() && b.get(0).trim().isEmpty()) leadingBlank = b.get(0);

        String trailingBlank = null;
        if (!a.isEmpty() && a.get(a.size() - 1).trim().isEmpty()) trailingBlank = a.get(a.size() - 1);
        else if (!b.isEmpty() && b.get(b.size() - 1).trim().isEmpty()) trailingBlank = b.get(b.size() - 1);

        SortedSet<String> imports = new TreeSet<>();
        for (String l : a) if (IMPORT_LINE.matcher(l).matches()) imports.add(l);
        for (String l : b) if (IMPORT_LINE.matcher(l).matches()) imports.add(l);

        List<String> out = new ArrayList<>(imports.size() + 2);
        if (leadingBlank != null) out.add(leadingBlank);
        out.addAll(imports);
        if (trailingBlank != null) out.add(trailingBlank);
        return out;
    }

    /**
     * Returns {@code true} if {@code superList} contains all elements of {@code subList} in order (not necessarily
     * contiguous).
     */
    private static boolean isSupersequence(List<String> superList, List<String> subList) {
        int j = 0;
        for (String s : superList) {
            if (j < subList.size() && Objects.equals(s, subList.get(j))) j++;
        }
        return j == subList.size();
    }
}
