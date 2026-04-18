package ai.brokk.analyzer.code_quality;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;

abstract class AbstractCloneDetectionSmellTest {

    protected List<IAnalyzer.CloneSmell> analyze(String pathA, String sourceA, String pathB, String sourceB) {
        return analyze(pathA, sourceA, pathB, sourceB, IAnalyzer.CloneSmellWeights.defaults());
    }

    protected List<IAnalyzer.CloneSmell> analyze(
            String pathA, String sourceA, String pathB, String sourceB, IAnalyzer.CloneSmellWeights weights) {
        try (var testProject = InlineTestProjectCreator.code(sourceA, pathA)
                .addFileContents(sourceB, pathB)
                .build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), pathA);
            return analyzer.findStructuralCloneSmells(file, weights);
        }
    }
}
