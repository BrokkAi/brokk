package io.github.jbellis.brokk;

import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.context.ContextHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    public Path getMasterRootPathForConfig() {
        return parent.getMasterRootPathForConfig();
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
    public void saveHistory(ContextHistory ch, UUID sessionId) {
        parent.saveHistory(ch, sessionId);
    }

    @Override
    public ContextHistory loadHistory(UUID sessionId, IContextManager contextManager) {
        return parent.loadHistory(sessionId, contextManager);
    }

    @Override
    public List<SessionInfo> listSessions() {
        return parent.listSessions();
    }

    @Override
    public SessionInfo newSession(String name) {
        return parent.newSession(name);
    }

    @Override
    public void renameSession(UUID sessionId, String newName) {
        parent.renameSession(sessionId, newName);
    }

    @Override
    public void deleteSession(UUID sessionIdToDelete) {
        parent.deleteSession(sessionIdToDelete);
    }

    @Override
    public SessionInfo copySession(UUID originalSessionId, String newSessionName) throws IOException {
        return parent.copySession(originalSessionId, newSessionName);
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
    public Service.ModelConfig getArchitectModelConfig() {
        return parent.getArchitectModelConfig();
    }

    @Override
    public void setArchitectModelConfig(Service.ModelConfig config) {
        parent.setArchitectModelConfig(config);
    }

    @Override
    public Service.ModelConfig getCodeModelConfig() {
        return parent.getCodeModelConfig();
    }

    @Override
    public void setCodeModelConfig(Service.ModelConfig config) {
        parent.setCodeModelConfig(config);
    }

    @Override
    public Service.ModelConfig getAskModelConfig() {
        return parent.getAskModelConfig();
    }

    @Override
    public void setAskModelConfig(Service.ModelConfig config) {
        parent.setAskModelConfig(config);
    }

    @Override
    public Service.ModelConfig getSearchModelConfig() {
        return parent.getSearchModelConfig();
    }

    @Override
    public void setSearchModelConfig(Service.ModelConfig config) {
        parent.setSearchModelConfig(config);
    }

    @Override
    public CpgRefresh getAnalyzerRefresh() {
        return parent.getAnalyzerRefresh();
    }

    @Override
    public void setAnalyzerRefresh(CpgRefresh value) {
        parent.setAnalyzerRefresh(value);
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
    public io.github.jbellis.brokk.IssueProvider getIssuesProvider() {
        return parent.getIssuesProvider();
    }

    @Override
    public void setIssuesProvider(io.github.jbellis.brokk.IssueProvider provider) {
        parent.setIssuesProvider(provider);
    }

    @Override
    public io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions getArchitectOptions() {
        return parent.getArchitectOptions();
    }

    @Override
    public boolean getArchitectRunInWorktree() {
        return parent.getArchitectRunInWorktree();
    }

    @Override
    public void setArchitectOptions(io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions options, boolean runInWorktree) {
        parent.setArchitectOptions(options, runInWorktree);
    }
}
