package io.github.jbellis.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSelectionPanelResolveTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path externalRoot;

    private IProject project;

    private static FileSelectionPanel.Config config(IProject project, boolean allowExternal, boolean multi) {
        return new FileSelectionPanel.Config(
                project,
                allowExternal,
                f -> true, // accept all files
                CompletableFuture.completedFuture(List.of()), // no autocomplete candidates needed here
                multi,
                bf -> {}, // no-op
                false,
                "");
    }

    @BeforeEach
    void setup() throws IOException {
        // Build a small project tree
        Files.createDirectories(projectRoot.resolve("dir"));
        Files.createDirectories(projectRoot.resolve("dir with space"));

        Files.writeString(projectRoot.resolve("a.txt"), "A");
        Files.writeString(projectRoot.resolve("b.java"), "B");
        Files.writeString(projectRoot.resolve("dir/c.txt"), "C");
        Files.writeString(projectRoot.resolve("dir with space/file name.txt"), "S");

        // External files
        Files.createDirectories(externalRoot.resolve("exdir"));
        Files.writeString(externalRoot.resolve("ext1.txt"), "E1");
        Files.writeString(externalRoot.resolve("exdir/e2.txt"), "E2");

        project = new TestProject(projectRoot, Language.JAVA);
    }

    private FileSelectionPanel panel(boolean allowExternal, boolean multi) {
        return new FileSelectionPanel(config(project, allowExternal, multi));
    }

    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    @Test
    void emptyInputReturnsEmpty() {
        var panel = panel(false, false);
        panel.setInputText("");
        assertTrue(panel.resolveAndGetSelectedFiles().isEmpty());
    }

    @Test
    void relativeExactFileResolvesToProjectFile() {
        var panel = panel(false, false);
        panel.setInputText("a.txt");
        var results = panel.resolveAndGetSelectedFiles();
        assertEquals(1, results.size());
        var bf = results.getFirst();
        assertTrue(bf instanceof ProjectFile);
        assertEquals(abs(projectRoot.resolve("a.txt")), bf.absPath().toString());
    }

    @Test
    void absoluteExactInsideProjectResolvesToProjectFile() {
        var panel = panel(false, false);
        var absolute = abs(projectRoot.resolve("b.java"));
        panel.setInputText(absolute);
        var results = panel.resolveAndGetSelectedFiles();
        assertEquals(1, results.size());
        var bf = results.getFirst();
        assertTrue(bf instanceof ProjectFile);
        assertEquals(absolute, bf.absPath().toString());
    }

    @Test
    void absoluteExactOutsideProjectDisallowedYieldsEmpty() throws IOException {
        var extFile = externalRoot.resolve("ext1.txt");
        var panel = panel(false, false); // external not allowed
        panel.setInputText(abs(extFile));
        assertTrue(panel.resolveAndGetSelectedFiles().isEmpty());
    }

    @Test
    void absoluteExactOutsideProjectAllowedYieldsExternalFile() throws IOException {
        var extFile = externalRoot.resolve("ext1.txt");
        var panel = panel(true, false); // external allowed
        panel.setInputText(abs(extFile));
        var results = panel.resolveAndGetSelectedFiles();
        assertEquals(1, results.size());
        var bf = results.getFirst();
        assertTrue(bf instanceof ExternalFile);
        assertEquals(abs(extFile), bf.absPath().toString());
    }

    @Test
    void relativeGlobMatchesProjectFiles() {
        var panel = panel(false, false);
        panel.setInputText("*.txt");
        var results = panel.resolveAndGetSelectedFiles();
        var absPaths = results.stream()
                .map(BrokkFile::absPath)
                .map(Path::toString)
                .sorted()
                .toList();
        assertEquals(List.of(abs(projectRoot.resolve("a.txt"))), absPaths);
        assertTrue(results.stream().allMatch(bf -> bf instanceof ProjectFile));
    }

    @Test
    void absoluteGlobInsideProjectMatchesProjectFiles() {
        var panel = panel(false, false);
        var pattern = projectRoot.resolve("dir").toString() + java.io.File.separator + "*.txt";
        panel.setInputText(pattern);
        var results = panel.resolveAndGetSelectedFiles();
        assertEquals(1, results.size());
        assertTrue(results.getFirst() instanceof ProjectFile);
        assertEquals(
                abs(projectRoot.resolve("dir/c.txt")),
                results.getFirst().absPath().toString());
    }

    @Test
    void absoluteGlobOutsideProjectHonorsExternalAllowance() {
        var pattern = externalRoot.resolve("exdir").toString() + java.io.File.separator + "e*.txt";

        var panelDisallow = panel(false, false);
        panelDisallow.setInputText(pattern);
        assertTrue(panelDisallow.resolveAndGetSelectedFiles().isEmpty(), "Disallow external should yield empty");

        var panelAllow = panel(true, false);
        panelAllow.setInputText(pattern);
        var results = panelAllow.resolveAndGetSelectedFiles();
        assertEquals(1, results.size());
        assertTrue(results.getFirst() instanceof ExternalFile);
        assertEquals(
                abs(externalRoot.resolve("exdir/e2.txt")),
                results.getFirst().absPath().toString());
    }

    @Test
    void multiSelectWithQuotesAndDedup() {
        var panel = panel(false, true);
        // Same file twice (one quoted), plus a file with spaces
        var quotedWithSpace = "\""
                + projectRoot.resolve("dir with space").resolve("file name.txt").toString() + "\"";
        panel.setInputText("a.txt " + quotedWithSpace + " a.txt");
        var results = panel.resolveAndGetSelectedFiles();
        assertEquals(2, results.size()); // de-duplicated
        var paths = results.stream().map(bf -> bf.absPath().toString()).sorted().toList();
        assertEquals(
                List.of(abs(projectRoot.resolve("a.txt")), abs(projectRoot.resolve("dir with space/file name.txt")))
                        .stream()
                        .sorted()
                        .toList(),
                paths);
        assertTrue(results.stream().allMatch(bf -> bf instanceof ProjectFile));
    }
}
