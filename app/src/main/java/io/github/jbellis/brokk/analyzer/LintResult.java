package io.github.jbellis.brokk.analyzer;

import java.util.List;
import java.util.Set;

/** Result of linting files, containing any diagnostic messages found. */
public record LintResult(List<LintDiagnostic> diagnostics) {

    /** Returns true if there are any ERROR level diagnostics. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == LintDiagnostic.Severity.ERROR);
    }

    /** Returns only the ERROR level diagnostics. */
    public List<LintDiagnostic> getErrors() {
        return diagnostics.stream()
                .filter(d -> d.severity() == LintDiagnostic.Severity.ERROR)
                .toList();
    }

    /** Returns diagnostics for specific files. */
    public List<LintDiagnostic> getDiagnosticsForFiles(Set<ProjectFile> files) {
        var filePaths = files.stream().map(ProjectFile::toString).collect(java.util.stream.Collectors.toSet());
        return diagnostics.stream().filter(d -> filePaths.contains(d.file())).toList();
    }

    /** Individual diagnostic message from linting. */
    public record LintDiagnostic(String file, int line, int column, Severity severity, String message, String code) {
        public enum Severity {
            ERROR,
            WARNING,
            INFO,
            HINT
        }
    }
}
