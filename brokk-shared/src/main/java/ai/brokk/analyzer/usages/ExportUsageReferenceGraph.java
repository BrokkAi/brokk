package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;

public interface ExportUsageReferenceGraph {

    ReferenceGraphResult findExportUsages(ProjectFile definingFile, String exportName, IAnalyzer analyzer)
            throws InterruptedException;
}
