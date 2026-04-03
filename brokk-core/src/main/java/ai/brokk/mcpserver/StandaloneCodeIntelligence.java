package ai.brokk.mcpserver;

import ai.brokk.ICodeIntelligence;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.ICoreProject;
import org.jetbrains.annotations.Nullable;

/**
 * Simple ICodeIntelligence implementation for the standalone MCP server.
 * Holds references to a project and its analyzer.
 */
public final class StandaloneCodeIntelligence implements ICodeIntelligence {
    private final ICoreProject project;
    private final IAnalyzer analyzer;

    public StandaloneCodeIntelligence(ICoreProject project, IAnalyzer analyzer) {
        this.project = project;
        this.analyzer = analyzer;
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return analyzer;
    }

    @Override
    public ICoreProject getProject() {
        return project;
    }

    @Override
    @Nullable
    public IGitRepo getRepo() {
        return project.getRepo();
    }
}
