package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.java.JdtUsageAnalyzer;
import ai.brokk.project.IProject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * A usage analyzer strategy that delegates to JDT for precise Java symbol resolution.
 */
@NullMarked
public final class JdtUsageAnalyzerStrategy implements UsageAnalyzer {

    private final IProject project;

    public JdtUsageAnalyzerStrategy(IProject project) {
        this.project = project;
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles) {
        if (overloads.isEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }

        // JDT handles overload resolution via signature matching when a target is provided.
        // We replicate existing UsageFinder behavior by using the first overload as the primary target.
        CodeUnit target = overloads.getFirst();
        Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(target, candidateFiles, project);

        return new FuzzyResult.Success(Map.of(target, hits));
    }
}
