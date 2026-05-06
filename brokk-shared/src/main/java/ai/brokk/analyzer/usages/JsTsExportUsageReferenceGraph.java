package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * JS/TS compatibility facade for the language-agnostic exported-symbol usage graph engine.
 */
public final class JsTsExportUsageReferenceGraph {
    private JsTsExportUsageReferenceGraph() {}

    public record Limits(int maxFiles, int maxHits, int maxReexportDepth) {
        public static Limits defaults() {
            var defaults = ExportUsageReferenceGraphEngine.Limits.defaults();
            return new Limits(defaults.maxFiles(), defaults.maxHits(), defaults.maxReexportDepth());
        }

        ExportUsageReferenceGraphEngine.Limits toEngineLimits() {
            return new ExportUsageReferenceGraphEngine.Limits(maxFiles, maxHits, maxReexportDepth);
        }
    }

    public static ExportUsageReferenceGraph create() {
        return create(Limits.defaults());
    }

    public static ExportUsageReferenceGraph create(Limits limits) {
        return new JsTsExportUsageReferenceGraphImpl(limits);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile, String exportName, IAnalyzer analyzer, Limits limits)
            throws InterruptedException {
        return findExportUsages(definingFile, exportName, null, analyzer, limits, null);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            IAnalyzer analyzer,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        return findExportUsages(definingFile, exportName, null, analyzer, limits, candidateFiles);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            @Nullable CodeUnit queryTarget,
            IAnalyzer analyzer,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            throw new IllegalArgumentException(
                    "Analyzer is not a JS/TS analyzer: " + analyzer.getClass().getName());
        }
        return ExportUsageReferenceGraphEngine.findExportUsages(
                definingFile,
                exportName,
                queryTarget,
                new JsTsExportUsageGraphAdapter(jsTs),
                limits.toEngineLimits(),
                candidateFiles);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            @Nullable CodeUnit queryTarget,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        return ExportUsageReferenceGraphEngine.findExportUsages(
                definingFile, exportName, queryTarget, adapter, limits.toEngineLimits(), candidateFiles);
    }
}
