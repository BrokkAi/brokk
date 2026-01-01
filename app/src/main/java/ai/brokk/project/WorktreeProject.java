package ai.brokk.project;

import ai.brokk.AbstractService;
import ai.brokk.IssueProvider;
import ai.brokk.SessionManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.mcp.McpConfig;
import ai.brokk.project.MainProject.DataRetentionPolicy;
import ai.brokk.util.StringDiskCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class WorktreeProject extends AbstractProject {
    private final MainProject parent;

    public WorktreeProject(Path root, MainProject parent) {
        super(root);
        this.parent = parent;
    }

    @Override
    public MainProject getParent() {
        return parent;
    }

    @Override
    public MainProject getMainProject() {
        return parent;
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        return parent.getAnalyzerLanguages();
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        parent.setAnalyzerLanguages(languages);
    }

    @Override
    public BuildAgent.BuildDetails loadBuildDetails() {
        return parent.loadBuildDetails();
    }

    @Override
    public void saveBuildDetails(BuildAgent.BuildDetails details) {
        parent.saveBuildDetails(details);
    }

    @Override
    public CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return parent.getBuildDetailsFuture();
    }

    @Override
    public BuildAgent.BuildDetails awaitBuildDetails() {
        return parent.awaitBuildDetails();
    }

    @Override
    public boolean hasBuildDetails() {
        return parent.hasBuildDetails();
    }

    @Override
    public DataRetentionPolicy getDataRetentionPolicy() {
        return parent.getDataRetentionPolicy();
    }

    @Override
    public void setDataRetentionPolicy(DataRetentionPolicy policy) {
        parent.setDataRetentionPolicy(policy);
    }

    @Override
    public String getStyleGuide() {
        return parent.getStyleGuide();
    }

    @Override
    public void saveStyleGuide(String styleGuide) {
        parent.saveStyleGuide(styleGuide);
    }

    @Override
    public String getReviewGuide() {
        return parent.getReviewGuide();
    }

    @Override
    public void saveReviewGuide(String reviewGuide) {
        parent.saveReviewGuide(reviewGuide);
    }

    @Override
    public boolean isDataShareAllowed() {
        return parent.isDataShareAllowed();
    }

    @Override
    public String getCommitMessageFormat() {
        return parent.getCommitMessageFormat();
    }

    @Override
    public void setCommitMessageFormat(String format) {
        parent.setCommitMessageFormat(format);
    }

    @Override
    public CodeAgentTestScope getCodeAgentTestScope() {
        return parent.getCodeAgentTestScope();
    }

    @Override
    public boolean isGitHubRepo() {
        return parent.isGitHubRepo();
    }

    @Override
    public boolean isGitIgnoreSet() {
        return parent.isGitIgnoreSet();
    }

    @Override
    public void setCodeAgentTestScope(CodeAgentTestScope selectedScope) {
        parent.setCodeAgentTestScope(selectedScope);
    }

    @Override
    public IssueProvider getIssuesProvider() {
        return parent.getIssuesProvider();
    }

    @Override
    public void setIssuesProvider(IssueProvider provider) {
        parent.setIssuesProvider(provider);
    }

    @Override
    public boolean getArchitectRunInWorktree() {
        return parent.getArchitectRunInWorktree();
    }

    @Override
    public StringDiskCache getDiskCache() {
        return parent.getDiskCache();
    }

    @Override
    public Set<Dependency> getLiveDependencies() {
        String liveDepsNames = workspaceProps.getProperty(LIVE_DEPENDENCIES_KEY);

        if (liveDepsNames == null) {
            // First access in this worktree: copy parent's current effective active set into this worktree
            var parentDeps = parent.getLiveDependencies(); // effective set from parent
            String names = parentDeps.stream()
                    .map(d -> d.root().getRelPath().getName(2).toString())
                    .collect(Collectors.joining(","));
            // Persist the copied list so future accesses are worktree-local
            workspaceProps.setProperty(LIVE_DEPENDENCIES_KEY, names);
            saveWorkspaceProperties();
            liveDepsNames = names;
        }

        return namesToDependencies(liveDepsNames);
    }

    @Override
    public void saveLiveDependencies(Set<Path> dependencyTopLevelDirs) {
        var names = dependencyTopLevelDirs.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.joining(","));
        workspaceProps.setProperty(LIVE_DEPENDENCIES_KEY, names);
        saveWorkspaceProperties();
        invalidateAllFiles();
    }

    @Override
    public List<List<String>> loadBlitzHistory() {
        return parent.loadBlitzHistory();
    }

    @Override
    public List<List<String>> addToBlitzHistory(String parallel, String post, int maxItems) {
        return parent.addToBlitzHistory(parallel, post, maxItems);
    }

    @Override
    public SessionManager getSessionManager() {
        return parent.getSessionManager();
    }

    @Override
    public void sessionsListChanged() {
        parent.sessionsListChanged();
    }

    @Override
    public boolean getPlanFirst() {
        return parent.getPlanFirst();
    }

    @Override
    public void setPlanFirst(boolean v) {
        parent.setPlanFirst(v);
    }

    @Override
    public boolean getSearch() {
        return parent.getSearch();
    }

    @Override
    public void setSearch(boolean v) {
        parent.setSearch(v);
    }

    @Override
    public boolean getInstructionsAskMode() {
        return parent.getInstructionsAskMode();
    }

    @Override
    public void setInstructionsAskMode(boolean ask) {
        parent.setInstructionsAskMode(ask);
    }

    @Override
    public McpConfig getMcpConfig() {
        return parent.getMcpConfig();
    }

    @Override
    public void setMcpConfig(McpConfig config) {
        parent.setMcpConfig(config);
    }

    @Override
    public boolean getAutoUpdateLocalDependencies() {
        return parent.getAutoUpdateLocalDependencies();
    }

    @Override
    public void setAutoUpdateLocalDependencies(boolean enabled) {
        parent.setAutoUpdateLocalDependencies(enabled);
    }

    @Override
    public boolean getAutoUpdateGitDependencies() {
        return parent.getAutoUpdateGitDependencies();
    }

    @Override
    public void setAutoUpdateGitDependencies(boolean enabled) {
        parent.setAutoUpdateGitDependencies(enabled);
    }

    @Override
    public AbstractService.ModelConfig getModelConfig(ModelProperties.ModelType modelType) {
        return parent.getModelConfig(modelType);
    }

    @Override
    public void setModelConfig(ModelProperties.ModelType modelType, AbstractService.ModelConfig config) {
        parent.setModelConfig(modelType, config);
    }
}
