package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;

/**
 * Thin OO adapter around {@link JsTsExportUsageReferenceGraph}.
 *
 * <p>This keeps orchestration language-agnostic by exposing a single interface ({@link ExportUsageReferenceGraph})
 * while allowing JS/TS to use a specialized implementation internally.
 */
public final class JsTsExportUsageReferenceGraphImpl implements ExportUsageReferenceGraph {

    private final JsTsExportUsageReferenceGraph.Limits limits;

    public JsTsExportUsageReferenceGraphImpl(JsTsExportUsageReferenceGraph.Limits limits) {
        this.limits = limits != null ? limits : JsTsExportUsageReferenceGraph.Limits.defaults();
    }

    public JsTsExportUsageReferenceGraphImpl() {
        this(JsTsExportUsageReferenceGraph.Limits.defaults());
    }

    @Override
    public ReferenceGraphResult findExportUsages(ProjectFile definingFile, String exportName, IAnalyzer analyzer)
            throws InterruptedException {
        return JsTsExportUsageReferenceGraph.findExportUsages(definingFile, exportName, analyzer, limits);
    }
}
