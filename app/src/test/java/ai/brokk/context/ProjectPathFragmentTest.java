package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments.ProjectPathFragment;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.Set;
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
        contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
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

    @Test
    void projectPathFragmentExcludesInnerClassAncestorSkeletons() throws IOException {
        // Define a file with an outer and inner class
        String relPath = "src/Outer.java";
        Path filePath = tempDir.resolve(relPath);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, "public class Outer extends OuterBase { class Inner extends InnerBase {} }");
        ProjectFile outerFile = new ProjectFile(tempDir, relPath);

        // Define ancestor files
        ProjectFile outerBaseFile = new ProjectFile(tempDir, "src/OuterBase.java");
        ProjectFile innerBaseFile = new ProjectFile(tempDir, "src/InnerBase.java");

        // Setup CodeUnits
        CodeUnit outerCls = CodeUnit.cls(outerFile, "com.example", "Outer");
        CodeUnit innerCls = CodeUnit.cls(outerFile, "com.example", "Inner");
        CodeUnit outerBase = CodeUnit.cls(outerBaseFile, "com.example", "OuterBase");
        CodeUnit innerBase = CodeUnit.cls(innerBaseFile, "com.example", "InnerBase");

        // Custom analyzer that returns only 'Outer' as a Top Level Declaration for the file
        TestAnalyzer customAnalyzer = new TestAnalyzer() {
            @Override
            public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
                if (file.equals(outerFile)) {
                    return List.of(outerCls);
                }
                return super.getTopLevelDeclarations(file);
            }

            @Override
            public Set<CodeUnit> getDeclarations(ProjectFile file) {
                if (file.equals(outerFile)) {
                    return Set.of(outerCls, innerCls);
                }
                return super.getDeclarations(file);
            }
        };

        customAnalyzer.addDeclaration(outerCls);
        customAnalyzer.addDeclaration(innerCls);
        customAnalyzer.setDirectAncestors(outerCls, List.of(outerBase));
        customAnalyzer.setDirectAncestors(innerCls, List.of(innerBase));
        customAnalyzer.setSkeleton(outerBase, "public class OuterBase {}");
        customAnalyzer.setSkeleton(innerBase, "public class InnerBase {}");

        TestContextManager cm = new TestContextManager(tempDir, new TestConsoleIO(), customAnalyzer);
        ProjectPathFragment fragment = new ProjectPathFragment(outerFile, cm);

        var supporting = fragment.supportingFragments();

        // Should contain OuterBase (ancestor of TLD) but NOT InnerBase (ancestor of inner class)
        var targetIds = supporting.stream()
                .filter(f -> f instanceof ContextFragments.SummaryFragment)
                .map(f -> ((ContextFragments.SummaryFragment) f).getTargetIdentifier())
                .toList();

        assertTrue(targetIds.contains("com.example.OuterBase"), "Should include OuterBase skeleton");
        assertTrue(!targetIds.contains("com.example.InnerBase"), "Should NOT include InnerBase skeleton");
    }
}
