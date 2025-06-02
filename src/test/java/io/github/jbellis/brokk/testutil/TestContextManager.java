package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.Language;

import java.nio.file.Path;

public final class TestContextManager implements IContextManager {
    private final TestProject project;

    public TestContextManager(Path projectRoot) {
        this.project = new TestProject(projectRoot, Language.JAVA);
    }

    @Override
    public TestProject getProject() {
        return project;
    }
}
