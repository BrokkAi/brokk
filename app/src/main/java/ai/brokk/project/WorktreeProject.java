package ai.brokk.project;

import ai.brokk.AbstractService;
import ai.brokk.IssueProvider;
import ai.brokk.SessionManager;
import ai.brokk.SessionRegistry;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.mcpclient.McpConfig;
import ai.brokk.project.MainProject.DataRetentionPolicy;
import ai.brokk.util.IStringDiskCache;
import ai.brokk.util.ShellConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public final class WorktreeProject extends AbstractProject {
    private final MainProject parent;

    public WorktreeProject(Path root, MainProject parent) {
        super(root, parent.getMasterRootPathForConfig());
        this.parent = parent;
    }

    /**
     * Copies analyzer cache files from the parent's storage locations to the worktree if they don't
     * already exist. This allows the worktree to start with a warm analyzer state.
     *
     * <p>This must be called off the EDT after project instantiation.
     */
    @Blocking
    public void warmStartAnalyzerCachesFromParent() {
        assert !SwingUtilities.isEventDispatchThread() : "warmStartAnalyzerCachesFromParent called on EDT";

        Set<Language> languages = parent.getAnalyzerLanguages();
        Set<Language> effectiveLanguages = languages.stream()
                .flatMap(l -> l instanceof Language.MultiLanguage ml ? ml.getLanguages().stream() : Stream.of(l))
                .filter(l -> l != Languages.NONE)
                .collect(Collectors.toSet());

        for (Language lang : effectiveLanguages) {
            try {
                Path source = lang.getStoragePath(parent);
                Path target = lang.getStoragePath(this);

                if (Files.exists(source) && !Files.exists(target)) {
                    Path targetParent = target.getParent();
                    if (targetParent != null && !Files.exists(targetParent)) {
                        Files.createDirectories(targetParent);
                    }
                    Files.copy(source, target);
                    logger.debug(
                            "Copied analyzer cache for {} from {} to {}", lang.name(), source.getFileName(), target);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn(
                        "Failed to copy analyzer cache for {} from parent project: {}", lang.name(), e.getMessage());
            }
        }
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
    public void invalidateAutoDetectedLanguages() {
        parent.invalidateAutoDetectedLanguages();
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
    public IStringDiskCache getDiskCache() {
        return parent.getDiskCache();
    }

    @Override
    public Set<Dependency> getLiveDependencies() {
        String liveDepsNames = workspaceProps.getProperty(LIVE_DEPENDENCIES_KEY);

        if (liveDepsNames == null) {
            // First access in this worktree: copy parent's current effective active set into this worktree
            var parentDeps = parent.getLiveDependencies(); // effective set from parent
            String names = parentDeps.stream()
                    .map(d -> {
                        Path fileName = d.root().getRelPath().getFileName();
                        assert fileName != null
                                : "Dependency path must have a file name: "
                                        + d.root().getRelPath();
                        return fileName.toString();
                    })
                    .collect(Collectors.joining(","));
            // Persist the copied list so future accesses are worktree-local
            workspaceProps.setProperty(LIVE_DEPENDENCIES_KEY, names);
            saveWorkspaceProperties();
            liveDepsNames = names;
        }

        return resolveDependencies(liveDepsNames);
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
    public SessionRegistry getSessionRegistry() {
        return parent.getSessionRegistry();
    }

    @Override
    public String getRemoteProjectName() {
        return parent.getRemoteProjectName();
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

    @Override
    public long getRunCommandTimeoutSeconds() {
        return parent.getRunCommandTimeoutSeconds();
    }

    @Override
    public long getTestCommandTimeoutSeconds() {
        return parent.getTestCommandTimeoutSeconds();
    }

    @Override
    public Set<ProjectFile> getAllOnDiskDependencies() {
        return parent.getAllOnDiskDependencies();
    }

    @Override
    public ShellConfig getShellConfig() {
        return parent.getShellConfig();
    }

    @Override
    public void setShellConfig(@Nullable ShellConfig config) {
        parent.setShellConfig(config);
    }

    @Override
    public boolean isGitConfigDeclined() {
        return parent.isGitConfigDeclined();
    }

    @Override
    public void setGitConfigDeclined(boolean declined) {
        parent.setGitConfigDeclined(declined);
    }
}
