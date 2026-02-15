package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.project.IProject;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
