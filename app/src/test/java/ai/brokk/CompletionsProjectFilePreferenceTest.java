package ai.brokk;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CompletionsProjectFilePreferenceTest {

    private static IProject projectWithJava() {
        return new IProject() {
            @Override
            public Set<Language> getAnalyzerLanguages() {
                return Set.of(Languages.JAVA);
            }
        };
    }

    @Test
    public void javaExtensionPreferredOverMd() {
        IProject project = projectWithJava();

        Path root = Path.of("/tmp/proj");
        ProjectFile javaFile = new ProjectFile(root, "BuildTool.java");
        ProjectFile mdFile = new ProjectFile(root, "baseline-testing.md");

        // Intentionally place the .md file first to ensure reordering by scoring
        List<ProjectFile> candidates = List.of(mdFile, javaFile);

        var results = Completions.scoreProjectFiles(
                "build",
                project,
                candidates,
                pf -> pf.getRelPath().getFileName().toString(), // short label
                pf -> pf.getRelPath().toString(), // long label
                pf -> new ShorthandCompletion(
                        null,
                        pf.getRelPath().getFileName().toString(),
                        pf.getRelPath().getFileName().toString()));

        Assertions.assertFalse(results.isEmpty(), "No completions returned");

        String first = results.getFirst().getReplacementText();
        Assertions.assertEquals("BuildTool.java", first, "Expected .java file ranked first");

        if (results.size() >= 2) {
            String second = results.get(1).getReplacementText();
            Assertions.assertEquals("baseline-testing.md", second);
        }
    }

    @Test
    public void multiLanguageProject_prefersKnownExtensionsOverMd() throws Exception {
        IProject project = InlineTestProjectCreator.code(
                        "package com.acme; public class BuildTool {}\n", "BuildTool.java")
                .addFileContents("def build_tool(): pass\n", "build_tool.py")
                .build();

        Path root = project.getRoot();
        ProjectFile javaFile = new ProjectFile(root, "BuildTool.java");
        ProjectFile pyFile = new ProjectFile(root, "build_tool.py");
        ProjectFile mdFile = new ProjectFile(root, "baseline-testing.md");

        // Intentionally shuffle order to ensure scoring reorders them
        List<ProjectFile> candidates = List.of(mdFile, pyFile, javaFile);

        var results = Completions.scoreProjectFiles(
                "build",
                project,
                candidates,
                pf -> pf.getRelPath().getFileName().toString(),
                pf -> pf.getRelPath().toString(),
                pf -> new ShorthandCompletion(
                        null,
                        pf.getRelPath().getFileName().toString(),
                        pf.getRelPath().getFileName().toString()));

        Assertions.assertFalse(results.isEmpty(), "No completions returned");

        var ordered =
                results.stream().map(ShorthandCompletion::getReplacementText).toList();

        int idxJava = ordered.indexOf("BuildTool.java");
        int idxPy = ordered.indexOf("build_tool.py");
        int idxMd = ordered.indexOf("baseline-testing.md");

        Assertions.assertTrue(idxJava >= 0, "Missing .java candidate");
        Assertions.assertTrue(idxPy >= 0, "Missing .py candidate");

        if (idxMd >= 0) {
            Assertions.assertTrue(idxJava < idxMd, ".java should be ranked before .md");
            Assertions.assertTrue(idxPy < idxMd, ".py should be ranked before .md");
        }
    }

    @Test
    public void unrelatedExtensionWithCamelCaseDoesNotOutrankPreferredExtension() {
        IProject project = projectWithJava();

        Path root = Path.of("/tmp/proj");
        ProjectFile javaFile = new ProjectFile(root, "BuildTool.java");
        ProjectFile svgFile = new ProjectFile(root, "BuildTool.svg");

        // Place .svg first to ensure scoring reorders them
        List<ProjectFile> candidates = List.of(svgFile, javaFile);

        var results = Completions.scoreProjectFiles(
                "build",
                project,
                candidates,
                pf -> pf.getRelPath().getFileName().toString(),
                pf -> pf.getRelPath().toString(),
                pf -> new ShorthandCompletion(
                        null,
                        pf.getRelPath().getFileName().toString(),
                        pf.getRelPath().getFileName().toString()));

        Assertions.assertFalse(results.isEmpty(), "No completions returned");

        var ordered =
                results.stream().map(ShorthandCompletion::getReplacementText).toList();

        int idxJava = ordered.indexOf("BuildTool.java");
        int idxSvg = ordered.indexOf("BuildTool.svg");

        Assertions.assertTrue(idxJava >= 0, "Missing .java candidate");
        Assertions.assertTrue(idxSvg >= 0, "Missing .svg candidate");

        Assertions.assertTrue(idxJava < idxSvg, ".java should be ranked before .svg");
    }
}
