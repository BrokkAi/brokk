package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.IAnalyzerWrapper;
import ai.brokk.IConsoleIO;
import ai.brokk.IssueProvider;
import ai.brokk.SessionManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.mcp.McpConfig;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.util.Environment;
import ai.brokk.util.StringDiskCache;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public interface IProject extends AutoCloseable {

    long DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS = Environment.DEFAULT_TIMEOUT.toSeconds();

    default IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    /**
     * Provides a string-specialized disk cache instance scoped to this project.
     *
     * <p>Implementations (MainProject) should return a properly initialized StringDiskCache.
     * WorktreeProject will forward to its MainProject parent.
     */
    default StringDiskCache getDiskCache() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the set of Brokk Language enums configured for the project.
     *
     * @return A set of Language enums.
     */
    default Set<Language> getAnalyzerLanguages() {
        throw new UnsupportedOperationException();
    }

    default Path getRoot() {
        throw new UnsupportedOperationException();
    }

    /** All files in the project, including decompiled dependencies that are not in the git repo. */
    default Set<ProjectFile> getAllFiles() {
        return Set.of();
    }

    /**
     * Returns true if this project contains no analyzable source files.
     * A project is considered "empty" when none of its files have extensions
     * matching any language in Languages.ALL_LANGUAGES (excluding NONE).
     *
     * This intentionally ignores configuration files like AGENTS.md, .brokk/**,
     * .gitignore, etc. since those don't have analyzable extensions.
     */
    default boolean isEmptyProject() {
        return false;
    }

    /**
     * Gets all analyzable files for the given language after gitignore and baseline filtering.
     * This method returns files that should be analyzed by the language-specific analyzer,
     * excluding files that are ignored by .gitignore or baseline exclusions.
     *
     * @param language The language to get analyzable files for
     * @return Set of ProjectFile objects that are analyzable for the given language
     */
    default Set<ProjectFile> getAnalyzableFiles(Language language) {
        var extensions = language.getExtensions();
        return getAllFiles().stream()
                .filter(pf -> extensions.contains(pf.extension()))
                .collect(Collectors.toSet());
    }

    default void invalidateAllFiles() {}

    /**
     * Check if a path (file or directory) is ignored by gitignore rules.
     *
     * @param relPath Path relative to project root
     * @return true if the path is ignored by gitignore rules, false otherwise
     */
    default boolean isGitignored(Path relPath) {
        return false; // Conservative default: assume not ignored
    }

    /**
     * Gets the structured build details inferred by the BuildAgent.
     *
     * This should only called directly by awaitBuildDetails and CM::createHeadless!
     * Everyone else should use awaitBuildDetails() instead.
     */
    default BuildAgent.BuildDetails loadBuildDetails() {
        return BuildAgent.BuildDetails.EMPTY;
    }

    default MainProject.DataRetentionPolicy getDataRetentionPolicy() {
        return MainProject.DataRetentionPolicy.MINIMAL;
    }

    default String getStyleGuide() {
        return "";
    }

    default String getReviewGuide() {
        throw new UnsupportedOperationException();
    }

    default void saveReviewGuide(String reviewGuide) {
        throw new UnsupportedOperationException();
    }

    default Path getMasterRootPathForConfig() {
        throw new UnsupportedOperationException();
    }

    default IProject getParent() {
        return this;
    }

    default MainProject getMainProject() {
        throw new UnsupportedOperationException();
    }

    default boolean hasGit() {
        return false;
    }

    default void saveBuildDetails(BuildAgent.BuildDetails details) {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    default ModelConfig getModelConfig(ModelType modelType) {
        return new ModelConfig("test-model");
    }

    default void setModelConfig(ModelType modelType, ModelConfig config) {
        throw new UnsupportedOperationException();
    }

    @Override
    default void close() {}

    default boolean hasBuildDetails() {
        return false;
    }

    default void saveStyleGuide(String styleGuide) {
        throw new UnsupportedOperationException();
    }

    default BuildAgent.BuildDetails awaitBuildDetails() {
        return BuildAgent.BuildDetails.EMPTY;
    }

    default boolean isDataShareAllowed() {
        return false;
    }

    default void setDataRetentionPolicy(MainProject.DataRetentionPolicy selectedPolicy) {}

    // JDK configuration: project-scoped JAVA_HOME setting (path or sentinel)
    default @Nullable String getJdk() {
        return null;
    }

    default void setJdk(@Nullable String jdkHome) {}

    default Rectangle getPreviewWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void savePreviewWindowBounds(JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default Rectangle getDiffWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveDiffWindowBounds(JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default Rectangle getOutputWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveOutputWindowBounds(JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default Optional<Rectangle> getMainWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveMainWindowBounds(JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default int getHorizontalSplitPosition() {
        return -1;
    }

    default void saveHorizontalSplitPosition(int position) {
        throw new UnsupportedOperationException();
    }

    default int getLeftVerticalSplitPosition() {
        return -1;
    }

    default void saveLeftVerticalSplitPosition(int position) {
        throw new UnsupportedOperationException();
    }

    default int getRightVerticalSplitPosition() {
        return -1;
    }

    default void saveRightVerticalSplitPosition(int position) {
        throw new UnsupportedOperationException();
    }

    default boolean getPlanFirst() {
        throw new UnsupportedOperationException();
    }

    default void setPlanFirst(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    default boolean getSearch() {
        throw new UnsupportedOperationException();
    }

    default void setSearch(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    default boolean getInstructionsAskMode() {
        throw new UnsupportedOperationException();
    }

    default void setInstructionsAskMode(boolean ask) {
        throw new UnsupportedOperationException();
    }

    default Optional<String> getActionMode() {
        return Optional.empty();
    }

    default void saveActionMode(String mode) {
        throw new UnsupportedOperationException();
    }

    default List<String> loadTextHistory() {
        return List.of();
    }

    default List<String> addToInstructionsHistory(String item, int maxItems) {
        throw new UnsupportedOperationException();
    }

    /* Blitz-history: (parallel instructions, post-processing instructions) */
    default List<List<String>> loadBlitzHistory() {
        return List.of();
    }

    default List<List<String>> addToBlitzHistory(
            String parallelInstructions, String postProcessingInstructions, int maxItems) {
        throw new UnsupportedOperationException();
    }

    // Git specific info
    default boolean isGitHubRepo() {
        return false;
    }

    default boolean isGitIgnoreSet() {
        return false;
    }

    default String getCommitMessageFormat() {
        throw new UnsupportedOperationException();
    }

    default CodeAgentTestScope getCodeAgentTestScope() {
        throw new UnsupportedOperationException();
    }

    default void setCommitMessageFormat(String text) {}

    default void setCodeAgentTestScope(CodeAgentTestScope selectedScope) {}

    default void setAnalyzerLanguages(Set<Language> languages) {}

    // Primary build language configuration
    default Language getBuildLanguage() {
        throw new UnsupportedOperationException();
    }

    default void setBuildLanguage(@Nullable Language language) {
        throw new UnsupportedOperationException();
    }

    // Command executor configuration: custom shell/interpreter for command execution
    default @Nullable String getCommandExecutor() {
        return null;
    }

    default void setCommandExecutor(@Nullable String executor) {}

    default @Nullable String getExecutorArgs() {
        return null;
    }

    default void setExecutorArgs(@Nullable String args) {}

    /** Gets a UI filter property for persistence across sessions (e.g., "issues.status"). */
    default @Nullable String getUiFilterProperty(String key) {
        return null;
    }

    /** Sets a UI filter property for persistence across sessions. */
    default void setUiFilterProperty(String key, @Nullable String value) {}

    default boolean getArchitectRunInWorktree() {
        throw new UnsupportedOperationException();
    }

    // MCP server configuration for this project
    default McpConfig getMcpConfig() {
        throw new UnsupportedOperationException();
    }

    default void setMcpConfig(McpConfig config) {
        throw new UnsupportedOperationException();
    }

    // New methods for the IssueProvider record
    default IssueProvider getIssuesProvider() { // Method name clash is intentional record migration
        throw new UnsupportedOperationException();
    }

    default void setIssuesProvider(IssueProvider provider) {
        throw new UnsupportedOperationException();
    }

    default SessionManager getSessionManager() {
        throw new UnsupportedOperationException();
    }

    default void sessionsListChanged() {
        throw new UnsupportedOperationException();
    }

    /**
     * Whether this project should automatically attempt to update dependencies that were imported
     * from local directories on disk. Implementations may persist this at the project level.
     *
     * Default is {@code false}.
     */
    default boolean getAutoUpdateLocalDependencies() {
        return false;
    }

    /**
     * Configure whether this project should automatically attempt to update dependencies that were
     * imported from local directories on disk.
     */
    default void setAutoUpdateLocalDependencies(boolean enabled) {}

    /**
     * Whether this project should automatically attempt to update dependencies that were imported
     * from GitHub repositories. Implementations may persist this at the project level.
     *
     * Default is {@code false}.
     */
    default boolean getAutoUpdateGitDependencies() {
        return false;
    }

    /**
     * Configure whether this project should automatically attempt to update dependencies that were
     * imported from GitHub repositories.
     */
    default void setAutoUpdateGitDependencies(boolean enabled) {}

    /**
     * Returns all on-disk dependency directories (immediate children of the dependencies folder).
     */
    default Set<ProjectFile> getAllOnDiskDependencies() {
        return Set.of();
    }

    /**
     * Returns the set of enabled (live) dependencies.
     */
    default Set<Dependency> getLiveDependencies() {
        return Set.of();
    }

    /**
     * Returns the set of exclusion patterns for code intelligence.
     * Patterns can be simple names (e.g., "node_modules") or globs (e.g., "*.svg").
     */
    default Set<String> getExclusionPatterns() {
        return Set.of();
    }

    /**
     * Returns exclusion patterns that are simple directory/file names (no wildcards).
     * Convenience method for callers that need Path-based exclusions.
     */
    default Set<String> getExcludedDirectories() {
        return getExclusionPatterns().stream()
                .filter(p -> !p.contains("*") && !p.contains("?"))
                .collect(Collectors.toSet());
    }

    /**
     * Returns exclusion patterns that contain wildcards (glob patterns).
     * Convenience method for callers that need only glob-style patterns.
     */
    default Set<String> getExcludedGlobPatterns() {
        return getExclusionPatterns().stream()
                .filter(p -> p.contains("*") || p.contains("?"))
                .collect(Collectors.toSet());
    }

    /**
     * Check if a path (file or directory) is excluded by any pattern.
     * Implementations should cache compiled patterns for efficiency.
     *
     * @param relativePath the relative path to check (e.g., "src/main/java" or "node_modules/foo/bar.js")
     * @param isDirectory true if the path is a directory (skips Extension pattern checks like *.svg)
     * @return true if the path is excluded
     */
    default boolean isPathExcluded(String relativePath, boolean isDirectory) {
        return false;
    }

    default IConsoleIO getConsoleIO() {
        throw new UnsupportedOperationException();
    }

    default void saveLiveDependencies(Set<Path> dependencyTopLevelDirs) {
        throw new UnsupportedOperationException();
    }

    /**
     * Updates the live dependencies set. If an analyzer is provided, also pauses/resumes
     * the watcher, computes file diffs, and updates the analyzer.
     *
     * @param newLiveDependencyDirs the complete desired set of live dependency directories
     * @param analyzerWrapper the analyzer to update, or null for persistence-only (CLI usage)
     * @return CompletableFuture that completes when all operations are done
     */
    default CompletableFuture<Void> updateLiveDependencies(
            Set<Path> newLiveDependencyDirs, @Nullable IAnalyzerWrapper analyzerWrapper) {
        throw new UnsupportedOperationException();
    }

    /**
     * Adds a dependency to the live set (merge semantics).
     * Used after importing a new dependency.
     *
     * @param dependencyName the name of the dependency directory to add
     * @param analyzerWrapper the analyzer to update, or null for persistence-only (CLI usage)
     * @return CompletableFuture that completes when the operation is done
     */
    default CompletableFuture<Void> addLiveDependency(
            String dependencyName, @Nullable IAnalyzerWrapper analyzerWrapper) {
        throw new UnsupportedOperationException();
    }

    /**
     * Obtains the user-defined run command timeout if set, or the default value otherwise.
     * @return the default timeout for how long a shell command may run for.
     */
    default long getRunCommandTimeoutSeconds() {
        return DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS;
    }

    enum CodeAgentTestScope {
        ALL,
        WORKSPACE;

        @Override
        public String toString() {
            return switch (this) {
                case ALL -> "Run All Tests";
                case WORKSPACE -> "Run Tests in Workspace";
            };
        }

        public static CodeAgentTestScope fromString(@Nullable String value, CodeAgentTestScope defaultScope) {
            if (value == null || value.isBlank()) return defaultScope;
            try {
                return CodeAgentTestScope.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return defaultScope;
            }
        }
    }

    /**
     * Represents a decompiled dependency included in the project's code intelligence, pairing its top-level root
     * directory with the detected primary Language.
     */
    record Dependency(ProjectFile root, Language language) {
        private static final Logger logger = LogManager.getLogger(Dependency.class);

        public Set<ProjectFile> files() {
            try (var pathStream = Files.walk(root.absPath())) {
                var masterRoot = root.getRoot();
                return pathStream
                        .filter(Files::isRegularFile)
                        .map(path -> new ProjectFile(masterRoot, masterRoot.relativize(path)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                logger.error("Error loading dependency files from {}: {}", root.absPath(), e.getMessage());
                return Set.of();
            }
        }
    }
}
