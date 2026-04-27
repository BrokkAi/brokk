package ai.brokk.analyzer.code_quality;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;

abstract class AbstractCloneDetectionSmellTest {

    protected List<IAnalyzer.CloneSmell> analyze(String pathA, String sourceA, String pathB, String sourceB) {
        return analyze(pathA, sourceA, pathB, sourceB, IAnalyzer.CloneSmellWeights.defaults());
    }

    protected List<IAnalyzer.CloneSmell> analyze(
            String pathA, String sourceA, String pathB, String sourceB, IAnalyzer.CloneSmellWeights weights) {
        try (var built =
                InlineCoreProject.code(sourceA, pathA).addFile(sourceB, pathB).build()) {
            IAnalyzer analyzer = built.analyzer();
            ProjectFile file = new ProjectFile(built.root(), pathA);
            return analyzer.findStructuralCloneSmells(file, weights);
        }
    }

    protected List<IAnalyzer.CloneSmell> analyzeBothRequested(
            String pathA, String sourceA, String pathB, String sourceB, IAnalyzer.CloneSmellWeights weights) {
        try (var built =
                InlineCoreProject.code(sourceA, pathA).addFile(sourceB, pathB).build()) {
            IAnalyzer analyzer = built.analyzer();
            ProjectFile fileA = new ProjectFile(built.root(), pathA);
            ProjectFile fileB = new ProjectFile(built.root(), pathB);
            return analyzer.findStructuralCloneSmells(List.of(fileA, fileB), weights);
        }
    }
}
