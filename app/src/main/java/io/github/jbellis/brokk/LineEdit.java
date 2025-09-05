package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.jetbrains.annotations.Nullable;

/**
 * Data model for line-based editing operations.
 * This sealed interface represents the discrete edit actions that can be applied.
 *
 * <ul>
 *   <li>`DeleteFile`: delete a file from the workspace
 *   <li>`EditFile`: replace the inclusive range `[beginLine, endLine]` with the given content
 * </ul>
 *
 * Lines are 1-based. While the external representation uses a `type` attribute (e.g.,
 * `type="insert"`), this internal model represents insertion operations by setting `endLine <
 * beginLine`. For example, `beginLine=1, endLine=0` inserts at the start of the file.
 */
public sealed interface LineEdit permits LineEdit.DeleteFile, LineEdit.EditFile {
    ProjectFile file();

    /**
     * Delete the specified file.
     */
    record DeleteFile(ProjectFile file) implements LineEdit { }

    /**
     * Anchor for validating addresses. address is the original address ("0", "1", ..., "$");
     * content is the exact current line text expected at that address.
     */
    record Anchor(String address, String content) { }

    /**
     * Replace a range of lines in the specified file with the provided content.
     * Lines are 1-based and the range is inclusive.
     *
     * <p>Insertion is modeled by {@code endLine < beginLine} (e.g., {@code beginLine=1, endLine=0} inserts at the start).
     * Anchors validate current file content at the addressed positions before applying. For deletions and insertions,
     * only the begin anchor is validated; for changes, both begin and end anchors are validated (for single-line changes,
     * the end anchor should equal the begin anchor).
     */
    record EditFile(ProjectFile file,
                    int beginLine,
                    int endLine,
                    String content,
                    Anchor beginAnchor,
                    @Nullable Anchor endAnchor) implements LineEdit {
        public EditFile {
            assert beginLine >= 1 : "beginLine must be >= 1";
            assert endLine >= 0 : "endLine must be >= 0";
        }
    }
}
