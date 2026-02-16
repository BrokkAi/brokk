package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.analyzer.TreeSitterStateIO;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.project.IProject;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JavaAnalyzerMultiStepUpdateTest {

    @Test
    void testMultiStepIncrementalUpdate() throws IOException {
        String baseClassContent =
                """
                package pkg1;
                public class BaseClass {
                    public void baseMethod() {}
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(baseClassContent, "pkg1/BaseClass.java")
                .build()) {
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            // Verify Initial Setup
            CodeUnit baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            String baseSkeleton = analyzer.getSkeleton(baseClassCu).orElse("");
            assertTrue(baseSkeleton.contains("baseMethod"), "Initial skeleton should contain baseMethod");

            // --- Step 1: Add new file ---
            ProjectFile derivedFile = new ProjectFile(project.getRoot(), "pkg2/DerivedClass.java");
            derivedFile.write(
                    """
                    package pkg2;
                    public class DerivedClass {
                        public void derivedMethod() {}
                    }
                    """);

            analyzer = analyzer.update();

            CodeUnit derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();
            assertNotNull(analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElse(null));

            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(
                    analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"),
                    "Derived skeleton should contain derivedMethod after Step 1");

            // --- Step 2: Add import ---
            derivedFile.write(
                    """
                    package pkg2;
                    import pkg1.BaseClass;
                    public class DerivedClass {
                        public void derivedMethod() {}
                    }
                    """);

            analyzer = analyzer.update();

            // Re-fetch CUs from new analyzer state
            baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();

            ImportAnalysisProvider importProvider =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow();
            var importedCus = importProvider.importedCodeUnitsOf(derivedFile);
            assertTrue(importedCus.contains(baseClassCu), "DerivedClass should import BaseClass");

            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"));

            // --- Step 3: Add inheritance ---
            derivedFile.write(
                    """
                    package pkg2;
                    import pkg1.BaseClass;
                    public class DerivedClass extends BaseClass {
                        public void derivedMethod() {}
                    }
                    """);

            analyzer = analyzer.update();

            // Re-fetch CUs
            baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();

            TypeHierarchyProvider hierarchyProvider =
                    analyzer.as(TypeHierarchyProvider.class).orElseThrow();
            List<CodeUnit> ancestors = hierarchyProvider.getDirectAncestors(derivedClassCu);

            assertTrue(ancestors.contains(baseClassCu), "BaseClass should be a direct ancestor of DerivedClass");

            // Verify all previous state still holds
            assertTrue(analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .isPresent());
            assertTrue(analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .isPresent());
            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"));

            importProvider = analyzer.as(ImportAnalysisProvider.class).orElseThrow();
            assertTrue(
                    importProvider.importedCodeUnitsOf(derivedFile).contains(baseClassCu),
                    "Import should still be resolved after inheritance change");
        }
    }

    @Test
    void testIncrementalSignatureLossWithDuplicateDeclarations() throws IOException {
        // 1. Build an inline project with an initial valid class definition (baseline)
        String initialContent =
                """
                package pkg;
                class Target {
                    void baseline() {}
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(initialContent, "pkg/Target.java").build()) {
            // 2. Create analyzer via AnalyzerCreator
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            // Verify baseline state
            CodeUnit targetCu =
                    analyzer.getDefinitions("pkg.Target").stream().findFirst().orElseThrow();
            assertTrue(
                    analyzer.getSkeleton(targetCu).orElse("").contains("baseline"),
                    "Baseline skeleton should contain baseline method");

            // 3. Locate the ProjectFile for pkg.Target
            ProjectFile targetFile =
                    AnalyzerUtil.getFileFor(analyzer, "pkg.Target").orElseThrow();

            // 4. Rewrite the file to contain two declarations: a forward-style one and a full definition.
            // This pattern (malformed header + full definition) is known to trigger the replacement logic
            // during incremental merge which might lead to signature/skeleton loss.
            targetFile.write(
                    """
                    package pkg;
                    class Target;
                    class Target {
                        void method() {}
                    }
                    """);

            // 5. Call analyzer.update(Set.of(targetFile)) - explicit update path
            analyzer = analyzer.update(Set.of(targetFile));

            // 6. Re-fetch CodeUnit and assert signatures/children are present
            targetCu = analyzer.getDefinitions("pkg.Target").stream()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("pkg.Target disappeared after update"));

            List<CodeUnit> children = analyzer.getDirectChildren(targetCu);
            boolean hasMethod = children.stream().anyMatch(cu -> cu.shortName().equals("Target.method"));
            String skeleton = analyzer.getSkeleton(targetCu).orElse("");

            // If this fails, it indicates that the replacement of 'class Target;' with 'class Target {...}'
            // in the same update pass caused the metadata (children/signatures) of the definition to be lost
            // because they were merged then immediately removed/overwritten incorrectly.
            assertTrue(hasMethod, "Updated Target should contain 'method' as a child");
            assertTrue(skeleton.contains("method"), "Updated Target skeleton should contain 'method'");
            assertFalse(skeleton.contains("baseline"), "Old baseline method should be gone");
        }
    }

    @Test
    void testIncrementalForwardDeclarationReplacement() throws IOException {
        // 1. Initialize with a minimal body-less class
        // (tree-sitter-java treats "class Target;" as malformed and may not emit a CodeUnit).
        String initialContent = """
                package pkg;
                class Target {}
                """;

        try (IProject project =
                InlineTestProjectCreator.code(initialContent, "pkg/Target.java").build()) {
            // 2. Create analyzer and verify initial state
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            CodeUnit targetCu = analyzer.getDefinitions("pkg.Target").stream()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find pkg.Target in initial state"));

            // JavaAnalyzer adds an implicit constructor for classes without one.
            List<CodeUnit> initialChildren = analyzer.getDirectChildren(targetCu);
            assertTrue(
                    initialChildren.stream().allMatch(CodeUnit::isFunction),
                    "Initial children should only be implicit constructor");

            // 3. Modify to a full class definition with a method
            ProjectFile file = new ProjectFile(project.getRoot(), "pkg/Target.java");
            file.write(
                    """
                    package pkg;
                    class Target {
                        void method() {}
                    }
                    """);

            // 4. Perform incremental update
            analyzer = analyzer.update();

            // 5. Retrieve updated CodeUnit
            targetCu = analyzer.getDefinitions("pkg.Target").stream()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find pkg.Target after incremental update"));

            // 6. Assert that the update logic preserved the new data (method and skeleton)
            List<CodeUnit> children = analyzer.getDirectChildren(targetCu);
            boolean hasMethod = children.stream().anyMatch(cu -> cu.shortName().equals("Target.method"));
            String skeleton = analyzer.getSkeleton(targetCu).orElse("");

            assertTrue(hasMethod, "Updated Target should contain 'method' as a child");
            assertTrue(skeleton.contains("method"), "Updated Target skeleton should contain 'method'");
        }
    }

    @Test
    void testMultiStepIncrementalUpdateWithSerializationRoundTrip(@TempDir Path tempDir) throws IOException {
        String baseClassContent =
                """
                package pkg1;
                public class BaseClass {
                    public void baseMethod() {}
                }
                """;

        // Use a generic filename to prove language is recovered from the DTO, not just the extension.
        Path storagePath = tempDir.resolve("analyzer-state.bin.lz4");

        try (IProject project = InlineTestProjectCreator.code(baseClassContent, "pkg1/BaseClass.java")
                .build()) {
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            // Initial Verification
            CodeUnit baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));

            // --- Step 1: Add new file + serialize/deserialize ---
            ProjectFile derivedFile = new ProjectFile(project.getRoot(), "pkg2/DerivedClass.java");
            derivedFile.write(
                    """
                    package pkg2;
                    public class DerivedClass {
                        public void derivedMethod() {}
                    }
                    """);

            // Save, Load, Reconstruct, Update
            TreeSitterStateIO.save(((TreeSitterAnalyzer) analyzer).snapshotState(), storagePath, Languages.JAVA);
            var loadedState = TreeSitterStateIO.load(storagePath).orElseThrow();
            analyzer = JavaAnalyzer.fromState(project, loadedState, IAnalyzer.ProgressListener.NOOP);
            analyzer = analyzer.update();

            // Verification
            baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            CodeUnit derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();

            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"));

            // --- Step 2: Add import + serialize/deserialize ---
            derivedFile.write(
                    """
                    package pkg2;
                    import pkg1.BaseClass;
                    public class DerivedClass {
                        public void derivedMethod() {}
                    }
                    """);

            TreeSitterStateIO.save(((TreeSitterAnalyzer) analyzer).snapshotState(), storagePath, Languages.JAVA);
            loadedState = TreeSitterStateIO.load(storagePath).orElseThrow();
            analyzer = JavaAnalyzer.fromState(project, loadedState, IAnalyzer.ProgressListener.NOOP);
            analyzer = analyzer.update();

            // Verification
            baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();

            ImportAnalysisProvider importProvider =
                    analyzer.as(ImportAnalysisProvider.class).orElseThrow();
            assertTrue(importProvider.importedCodeUnitsOf(derivedFile).contains(baseClassCu));
            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"));

            // --- Step 3: Add inheritance + serialize/deserialize ---
            derivedFile.write(
                    """
                    package pkg2;
                    import pkg1.BaseClass;
                    public class DerivedClass extends BaseClass {
                        public void derivedMethod() {}
                    }
                    """);

            TreeSitterStateIO.save(((TreeSitterAnalyzer) analyzer).snapshotState(), storagePath, Languages.JAVA);
            loadedState = TreeSitterStateIO.load(storagePath).orElseThrow();
            analyzer = JavaAnalyzer.fromState(project, loadedState, IAnalyzer.ProgressListener.NOOP);
            analyzer = analyzer.update();

            // Final Verification
            baseClassCu = analyzer.getDefinitions("pkg1.BaseClass").stream()
                    .findFirst()
                    .orElseThrow();
            derivedClassCu = analyzer.getDefinitions("pkg2.DerivedClass").stream()
                    .findFirst()
                    .orElseThrow();

            TypeHierarchyProvider hierarchyProvider =
                    analyzer.as(TypeHierarchyProvider.class).orElseThrow();
            assertTrue(hierarchyProvider.getDirectAncestors(derivedClassCu).contains(baseClassCu));

            assertTrue(analyzer.getSkeleton(baseClassCu).orElse("").contains("baseMethod"));
            assertTrue(analyzer.getSkeleton(derivedClassCu).orElse("").contains("derivedMethod"));
            assertTrue(analyzer.as(ImportAnalysisProvider.class)
                    .orElseThrow()
                    .importedCodeUnitsOf(derivedFile)
                    .contains(baseClassCu));
        }
    }
}
