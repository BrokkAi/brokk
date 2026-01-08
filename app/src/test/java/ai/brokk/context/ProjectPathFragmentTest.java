package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments.ProjectPathFragment;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectPathFragmentTest {

    @TempDir
    Path tempDir;

    private TestContextManager contextManager;
    private TestAnalyzer analyzer;
    private ProjectFile projectFile;

    @BeforeEach
    void setUp() throws IOException {
        analyzer = new TestAnalyzer();

        // Create a test file in the temporary directory
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        
        // Use consistent relative paths
        String myClassRelPath = "src/MyClass.java";
        String baseClassRelPath = "src/Base.java";

        Path filePath = tempDir.resolve(myClassRelPath);
        Files.writeString(filePath, "public class MyClass extends Base {}");
        projectFile = new ProjectFile(tempDir, myClassRelPath);

        // Create ancestor file
        Path baseFileDisk = tempDir.resolve(baseClassRelPath);
        Files.writeString(baseFileDisk, "public class Base {}");
        ProjectFile baseFile = new ProjectFile(tempDir, baseClassRelPath);

        // Setup test data
        CodeUnit myClass = CodeUnit.cls(projectFile, "com.example", "MyClass");
        CodeUnit baseClass = CodeUnit.cls(baseFile, "com.example", "Base");

        // Configure analyzer with declarations
        analyzer.addDeclaration(myClass);
        analyzer.addDeclaration(baseClass);
        analyzer.setDirectAncestors(myClass, List.of(baseClass));
        analyzer.setSkeleton(baseClass, "public class Base {\n  void baseMethod();\n}");

        // Create ContextManager with the configured analyzer
        contextManager = new TestContextManager(tempDir, new ai.brokk.testutil.TestConsoleIO(), analyzer);
    }

    @Test
    void projectPathFragmentIncludesAncestorSkeletons() {
        ProjectPathFragment fragment = new ProjectPathFragment(projectFile, contextManager);

        // Verify text output
        String text = fragment.text().join();
        assertCodeContains(text, "public class MyClass extends Base {}");
        
        // Note: FILE_SKELETONS summary type used by ProjectPathFragment does not include 
        // the "// Direct ancestors of..." header, just the skeletons grouped by package.
        assertCodeContains(text, "package com.example;");
        assertCodeContains(text, "public class Base {");

        // Verify sources() includes both
        var sources = fragment.sources().join();
        assertTrue(sources.stream().anyMatch(cu -> cu.shortName().equals("MyClass")), "Should contain primary class");
        assertTrue(sources.stream().anyMatch(cu -> cu.shortName().equals("Base")), "Should contain ancestor class");

        // Verify files() includes both
        var files = fragment.files().join();
        assertTrue(files.stream().anyMatch(f -> f.equals(projectFile)), "Should contain primary file");
        assertTrue(files.stream().anyMatch(f -> f.equals(new ProjectFile(tempDir, "src/Base.java"))), "Should contain ancestor file");
    }
}
