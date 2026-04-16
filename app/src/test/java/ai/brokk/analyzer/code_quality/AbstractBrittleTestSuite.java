package ai.brokk.analyzer.code_quality;

import ai.brokk.analyzer.IAnalyzer;
import java.util.List;

abstract class AbstractBrittleTestSuite {

    protected List<IAnalyzer.TestAssertionSmell> analyze(String source) {
        return analyze(source, IAnalyzer.TestAssertionWeights.defaults());
    }

    protected List<IAnalyzer.TestAssertionSmell> analyze(String source, IAnalyzer.TestAssertionWeights weights) {
        return analyze(source, defaultTestPath(), weights);
    }

    protected List<IAnalyzer.TestAssertionSmell> analyze(String source, String path) {
        return analyze(source, path, IAnalyzer.TestAssertionWeights.defaults());
    }

    protected boolean hasReason(List<IAnalyzer.TestAssertionSmell> findings, String reason) {
        return findings.stream().anyMatch(finding -> finding.reasons().contains(reason));
    }

    protected boolean hasKind(List<IAnalyzer.TestAssertionSmell> findings, String kind) {
        return findings.stream().anyMatch(finding -> finding.assertionKind().equals(kind));
    }

    protected IAnalyzer.TestAssertionWeights defaultWeights() {
        return IAnalyzer.TestAssertionWeights.defaults();
    }

    protected String defaultTestPath() {
        return "com/example/SampleTest.java";
    }

    protected abstract List<IAnalyzer.TestAssertionSmell> analyze(
            String source, String path, IAnalyzer.TestAssertionWeights weights);
}
