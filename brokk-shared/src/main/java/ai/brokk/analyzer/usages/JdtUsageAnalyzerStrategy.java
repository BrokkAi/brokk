package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.java.JdtUsageAnalyzer;
import ai.brokk.project.ICoreProject;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A usage analyzer strategy that delegates to JDT for precise Java symbol resolution.
 */
public final class JdtUsageAnalyzerStrategy implements UsageAnalyzer {

    private final ICoreProject project;

    public JdtUsageAnalyzerStrategy(ICoreProject project) {
        this.project = project;
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles, int maxUsages) {
        if (overloads.isEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }

        // JDT handles overload resolution via signature matching when a target is provided.
        // We replicate existing UsageFinder behavior by using the first overload as the primary target.
        CodeUnit target = overloads.getFirst();
        try {
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(target, candidateFiles, project);
            if (hits.size() > maxUsages) {
                return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
            }
            return new FuzzyResult.Success(Map.of(target, hits));
        } catch (Exception e) {
            return new FuzzyResult.Failure(target.fqName(), e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
