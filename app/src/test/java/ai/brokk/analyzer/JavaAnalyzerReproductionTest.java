package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaAnalyzerReproductionTest {

    @Test
    void testForwardDeclarationReplacementDoesNotLoseSignatures() throws IOException {
        // This simulates a scenario common during editing where a partial/broken class
        // exists in the same file as a full one, or a "forward declaration" style pattern.
        // The bug occurs when the analyzer tries to replace the "body-less" version with the
        // "with-body" version, but uses an equality check that triggers removal of the
        // newly added properties.
        String content =
                """
                package pkg;

                // A body-less/malformed declaration that might be picked up
                class Target;

                // The full definition
                class Target {
                    public void method() {}
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(content, "pkg/Target.java").build()) {
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            Set<CodeUnit> definitions = analyzer.getDefinitions("pkg.Target");
            assertEquals(1, definitions.size(), "Should only have one consolidated definition for pkg.Target");

            CodeUnit targetCu = definitions.iterator().next();

            // Verify signatures are present
            List<CodeUnit> children = analyzer.getDirectChildren(targetCu);
            boolean hasMethod = children.stream().anyMatch(cu -> cu.shortName().equals("Target.method"));

            String skeleton = analyzer.getSkeleton(targetCu).orElse("");

            assertTrue(hasMethod, "Target class should have 'method' as a child");
            assertTrue(skeleton.contains("method"), "Skeleton should contain 'method'");
            assertFalse(skeleton.isBlank(), "Skeleton should not be blank");
        }
    }

    @Test
    void testNestedForwardDeclarationReplacementDoesNotLoseSignatures() throws IOException {
        String content =
                """
                package pkg;
                class Outer {
                    class Inner;
                    class Inner {
                        void nested() {}
                    }
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(content, "pkg/Outer.java").build()) {
            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            CodeUnit innerCu = analyzer.getDefinitions("pkg.Outer.Inner").stream()
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find pkg.Outer.Inner"));

            List<CodeUnit> children = analyzer.getDirectChildren(innerCu);
            assertTrue(
                    children.stream().anyMatch(cu -> cu.shortName().endsWith("nested")),
                    "Inner class should have 'nested' method even after replacement of forward decl");

            String skeleton = analyzer.getSkeleton(innerCu).orElse("");
            assertTrue(skeleton.contains("nested"), "Inner skeleton should contain 'nested'");
        }
    }
}
