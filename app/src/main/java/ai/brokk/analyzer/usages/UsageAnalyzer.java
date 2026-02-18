package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface UsageAnalyzer {
    /**
     * Finds usages for a set of overloads within the provided candidate files.
     */
    FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles) throws InterruptedException;
}
