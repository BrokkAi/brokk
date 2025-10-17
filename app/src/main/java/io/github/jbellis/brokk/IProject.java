package io.github.jbellis.brokk;

import com.jakewharton.disklrucache.DiskLruCache;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.mcp.McpConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public interface IProject extends AutoCloseable {

    default IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    /**
     * Provides a DiskLruCache instance scoped to this project.
     *
     * <p>Implementations (MainProject) should return a properly initialized DiskLruCache. WorktreeProject will forward
     * to its MainProject parent.
     */
    default DiskLruCache getDiskCache() {
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
     * Gets all files in the project that match the given language's extensions. This is a convenience method that
     * filters getAllFiles() by the language's file extensions.
     *
     * @param language The language to filter files for
     * @return Set of ProjectFiles that match the language's extensions
     */
    default Set<ProjectFile> getFiles(Language language) {
        var extensions = language.getExtensions();
        return getAllFiles().stream()
                .filter(pf -> extensions.contains(pf.extension()))
                .collect(Collectors.toSet());
    }

    default void invalidateAllFiles() {}

    /**
     * Gets the structured build details inferred by the BuildAgent.
     *
     * @return BuildDetails record, potentially BuildDetails.EMPTY if not found or on error.
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

    default Service.ModelConfig getCodeModelConfig() {
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

    default java.awt.Rectangle getPreviewWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void savePreviewWindowBounds(javax.swing.JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default java.awt.Rectangle getDiffWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveDiffWindowBounds(javax.swing.JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default java.awt.Rectangle getOutputWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveOutputWindowBounds(javax.swing.JFrame frame) {
        throw new UnsupportedOperationException();
    }

    default java.util.Optional<java.awt.Rectangle> getMainWindowBounds() {
        throw new UnsupportedOperationException();
    }

    default void saveMainWindowBounds(javax.swing.JFrame frame) {
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

    default List<String> loadTextHistory() {
        return List.of();
    }

    default List<String> addToInstructionsHistory(String item, int maxItems) {
        throw new UnsupportedOperationException();
    }

    /* Blitz-history: (parallel instructions, post-processing instructions) */
    default java.util.List<java.util.List<String>> loadBlitzHistory() {
        return java.util.List.of();
    }

    default java.util.List<java.util.List<String>> addToBlitzHistory(
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

    default void setCodeModelConfig(Service.ModelConfig modelConfig) {
        throw new UnsupportedOperationException();
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
    default io.github.jbellis.brokk.IssueProvider
            getIssuesProvider() { // Method name clash is intentional record migration
        throw new UnsupportedOperationException();
    }

    default void setIssuesProvider(io.github.jbellis.brokk.IssueProvider provider) {
        throw new UnsupportedOperationException();
    }

    default SessionManager getSessionManager() {
        throw new UnsupportedOperationException();
    }

    default void sessionsListChanged() {
        throw new UnsupportedOperationException();
    }

    default Set<String> getExcludedDirectories() {
        return Set.of();
    }

    default IConsoleIO getConsoleIO() {
        throw new UnsupportedOperationException();
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

        public java.util.Set<ProjectFile> files() {
            try (var pathStream = Files.walk(root.absPath())) {
                var masterRoot = root.getRoot();
                return pathStream
                        .filter(Files::isRegularFile)
                        .map(path -> new ProjectFile(masterRoot, masterRoot.relativize(path)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                logger.error("Error loading dependency files from {}: {}", root.absPath(), e.getMessage());
                return java.util.Set.of();
            }
        }
    }
}
