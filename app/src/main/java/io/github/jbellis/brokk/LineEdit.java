package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;

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
     * Replace a range of lines in the specified file with the provided content.
     * Lines are 1-based and the range is inclusive.
     *
     * <p>This is the internal representation; insertion is modeled by `endLine < beginLine` (e.g.,
     * `beginLine=1, endLine=0` inserts at the start). The external representation seen by the LLM uses
     * a `type` attribute instead.
     */
    record EditFile(ProjectFile file, int beginLine, int endLine, String content) implements LineEdit {
        public EditFile {
            assert beginLine >= 1 : "beginLine must be >= 1";
            assert endLine >= 0 : "endLine must be >= 0";
        }
    }
}
