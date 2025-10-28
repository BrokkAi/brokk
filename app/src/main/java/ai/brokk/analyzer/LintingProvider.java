package ai.brokk.analyzer;

import java.util.List;

/** Implemented by analyzers that can readily provide source code linting. */
public interface LintingProvider extends CapabilityProvider {

    /**
     * Lint the specified files and return any diagnostics found. This method should trigger analysis of the files and
     * collect any compiler/linter errors, warnings, or other diagnostics.
     *
     * @param files the files to lint
     * @return a LintResult containing any diagnostics found
     */
    LintResult lintFiles(List<ProjectFile> files);
}
