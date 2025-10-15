package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Llm;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class BuildAgentExclusionsTest {

    @Test
    void sanitize_keeps_globs_and_strips_leading_slashes() {
        // Glob should be preserved as-is (no InvalidPathException), leading slash removed
        assertEquals("**/.idea", BuildAgent.sanitizeExclusion("**/.idea"));
        assertEquals("dist", BuildAgent.sanitizeExclusion("/dist"));
        assertEquals("nbproject/private", BuildAgent.sanitizeExclusion("\\nbproject\\private"));
        assertEquals("target", BuildAgent.sanitizeExclusion("  target  "));
    }

    @Test
    void combine_and_sanitize_merges_and_normalizes() {
        var baseline = List.of("**/target", "docs");
        var extras = List.of("**/.idea", " /dist ");
        Set<String> combined = BuildAgent.combineAndSanitizeExcludes(baseline, extras);

        // Order is maintained by LinkedHashSet; presence is what matters
        assertTrue(combined.contains("**/target"));
        assertTrue(combined.contains("docs"));
        assertTrue(combined.contains("**/.idea"));
        assertTrue(combined.contains("dist"));
    }

    @Test
    void reportBuildDetails_accepts_globs_without_throwing() {
        // Minimal stubs; reportBuildDetails only needs the exclusions and does not use these dependencies.
        IProject dummyProject = new IProject() {
            @Override public java.nio.file.Path getRoot() { return java.nio.file.Paths.get(".").toAbsolutePath().normalize(); }
            @Override public io.github.jbellis.brokk.git.IGitRepo getRepo() { throw new UnsupportedOperationException(); }
            @Override public boolean hasGit() { return false; }
            @Override public void close() {}
            @Override public java.util.Set<io.github.jbellis.brokk.analyzer.ProjectFile> getAllFiles() { return java.util.Set.of(); }
            @Override public void invalidateAllFiles() {}
            @Override public java.util.Set<String> getExcludedDirectories() { return java.util.Set.of(); }
            @Override public io.github.jbellis.brokk.analyzer.Language getBuildLanguage() { return io.github.jbellis.brokk.analyzer.Languages.NONE; }
            @Override public void setBuildLanguage(io.github.jbellis.brokk.analyzer.Language language) {}
            @Override public @org.jetbrains.annotations.Nullable String getJdk() { return null; }
            @Override public void setJdk(@org.jetbrains.annotations.Nullable String jdkHome) {}
            @Override public @org.jetbrains.annotations.Nullable String getCommandExecutor() { return null; }
            @Override public void setCommandExecutor(@org.jetbrains.annotations.Nullable String executor) {}
            @Override public @org.jetbrains.annotations.Nullable String getExecutorArgs() { return null; }
            @Override public void setExecutorArgs(@org.jetbrains.annotations.Nullable String args) {}
            @Override public java.util.Optional<java.awt.Rectangle> getMainWindowBounds() { return java.util.Optional.empty(); }
            @Override public java.awt.Rectangle getPreviewWindowBounds() { return new java.awt.Rectangle(0,0,0,0); }
            @Override public java.awt.Rectangle getDiffWindowBounds() { return new java.awt.Rectangle(0,0,0,0); }
            @Override public java.awt.Rectangle getOutputWindowBounds() { return new java.awt.Rectangle(0,0,0,0); }
            @Override public void saveMainWindowBounds(javax.swing.JFrame window) {}
            @Override public void savePreviewWindowBounds(javax.swing.JFrame window) {}
            @Override public void saveDiffWindowBounds(javax.swing.JFrame frame) {}
            @Override public void saveOutputWindowBounds(javax.swing.JFrame frame) {}
            @Override public void saveHorizontalSplitPosition(int position) {}
            @Override public int getHorizontalSplitPosition() { return -1; }
            @Override public void saveRightVerticalSplitPosition(int position) {}
            @Override public int getRightVerticalSplitPosition() { return -1; }
            @Override public void saveLeftVerticalSplitPosition(int position) {}
            @Override public int getLeftVerticalSplitPosition() { return -1; }
            @Override public java.util.List<String> loadTextHistory() { return java.util.List.of(); }
            @Override public java.util.List<String> addToInstructionsHistory(String item, int maxItems) { return java.util.List.of(); }
            @Override public io.github.jbellis.brokk.agents.BuildAgent.BuildDetails loadBuildDetails() { return io.github.jbellis.brokk.agents.BuildAgent.BuildDetails.EMPTY; }
            @Override public void saveBuildDetails(io.github.jbellis.brokk.agents.BuildAgent.BuildDetails details) {}
            @Override public io.github.jbellis.brokk.IProject.CodeAgentTestScope getCodeAgentTestScope() { return io.github.jbellis.brokk.IProject.CodeAgentTestScope.WORKSPACE; }
            @Override public void setCodeAgentTestScope(io.github.jbellis.brokk.IProject.CodeAgentTestScope scope) {}
            @Override public java.nio.file.Path getMasterRootPathForConfig() { return getRoot(); }
        };
        Llm dummyLlm = null;
        ToolRegistry dummyRegistry = null;

        var agent = new BuildAgent(dummyProject, dummyLlm, dummyRegistry);

        // Should not throw on Windows or any OS even with glob patterns
        assertDoesNotThrow(() -> agent.reportBuildDetails("", "", "", java.util.List.of("**/.idea", "**/target", "target")));
    }
}
