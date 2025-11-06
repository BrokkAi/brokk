package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodePromptsTest {

    @Test
    void syntaxAwareEnabledWhenAllEditableFilesAreJava(@TempDir Path tempDir) throws Exception {
        var project = new TestProject(tempDir, Languages.JAVA);
        var files = new HashSet<ProjectFile>();

        var f1 = new ProjectFile(tempDir, "src/main/java/com/acme/A.java");
        f1.create();
        f1.write("package com.acme;\npublic class A {}");

        var f2 = new ProjectFile(tempDir, "src/main/java/com/acme/B.java");
        f2.create();
        f2.write("package com.acme;\npublic class B {}");

        files.add(f1);
        files.add(f2);

        var flags = CodePrompts.instructionsFlags(project, files);
        assertTrue(
                flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                "SYNTAX_AWARE should be enabled when all editable files are .java");
    }

    @Test
    void syntaxAwareEnabledWhenAtLeastOneEditableFileIsJava(@TempDir Path tempDir) throws Exception {
        var project = new TestProject(tempDir, Languages.JAVA);
        Set<ProjectFile> files = new HashSet<>();

        var javaFile = new ProjectFile(tempDir, "src/main/java/com/acme/A.java");
        javaFile.create();
        javaFile.write("package com.acme;\npublic class A {}");

        var nonJavaFile = new ProjectFile(tempDir, "docs/readme.md");
        nonJavaFile.create();
        nonJavaFile.write("# Readme");

        files.add(javaFile);
        files.add(nonJavaFile);

        var flags = CodePrompts.instructionsFlags(project, files);
        assertTrue(
                flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                "SYNTAX_AWARE should be enabled when at least one editable file is .java");
    }

    @Test
    void syntaxAwareDisabledWhenNoJavaEditableFiles(@TempDir Path tempDir) throws Exception {
        var project = new TestProject(tempDir, Languages.JAVA);
        Set<ProjectFile> files = new HashSet<>();

        var nonJavaFile = new ProjectFile(tempDir, "docs/readme.md");
        nonJavaFile.create();
        nonJavaFile.write("# Readme");

        files.add(nonJavaFile);

        var flags = CodePrompts.instructionsFlags(project, files);
        assertFalse(
                flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                "SYNTAX_AWARE should be disabled when no editable files are Java");
    }

    @Test
    void syntaxAwareDisabledWhenNoEditableFiles(@TempDir Path tempDir) throws Exception {
        var project = new TestProject(tempDir, Languages.JAVA);
        var files = Set.<ProjectFile>of();

        var flags = CodePrompts.instructionsFlags(project, files);
        assertFalse(
                flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                "SYNTAX_AWARE should be disabled when there are no editable files");
    }
}
