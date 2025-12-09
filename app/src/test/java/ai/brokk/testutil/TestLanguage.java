package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.Set;

public class TestLanguage implements Language {
    @Override
    public Set<String> getExtensions() {
        return Set.of("fake");
    }

    @Override
    public String name() {
        return "FakeLanguage";
    }

    @Override
    public String internalName() {
        return "fake";
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        throw new UnsupportedOperationException("Not needed for this test");
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        throw new UnsupportedOperationException("Not needed for this test");
    }

    @Override
    public Path getStoragePath(IProject project) {
        return project.getRoot().resolve(".brokk/cache/fake.cache");
    }
}
