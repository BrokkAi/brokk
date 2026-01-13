package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        // Verify text output: should only contain the file content
        String text = fragment.text().join();
        assertCodeContains(text, "public class MyClass extends Base {}");

        // Ancestor skeletons should NOT be in text, but in supporting fragments
        // Verify supportingFragments
        var supporting = fragment.supportingFragments();
        assertEquals(1, supporting.size(), "Should have one supporting fragment for Base");

        var ancestorFragment =
                (ContextFragments.SummaryFragment) supporting.iterator().next();
        assertEquals("com.example.Base", ancestorFragment.getTargetIdentifier());

        // Verify sources() includes only MyClass
        var sources = fragment.sources().join();
        assertTrue(sources.stream().anyMatch(cu -> cu.shortName().equals("MyClass")), "Should contain primary class");
        assertEquals(1, sources.size());

        // Verify files() includes only projectFile
        var files = fragment.files().join();
        assertTrue(files.stream().anyMatch(f -> f.equals(projectFile)), "Should contain primary file");
        assertEquals(1, files.size());
    }
}
