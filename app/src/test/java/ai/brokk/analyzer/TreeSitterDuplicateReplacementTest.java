package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;
import org.junit.jupiter.api.Test;

/**
 * Reproduces a bug scenario where triggering replacement for a specific overload
 * (same signature and fqName) should not accidentally remove other overloads with the same fqName.
 */
public class TreeSitterDuplicateReplacementTest {

    /**
     * Verifies that replacing a specific overload (same signature) does not remove other overloads
     * with the same fully qualified name.
     */
    /**
     * Test analyzer that forces replacement ONLY for matching signatures.
     */
    private static class ReplacingJavaAnalyzer extends JavaAnalyzer {
        ReplacingJavaAnalyzer(IProject project) {
            super(project, ProgressListener.NOOP);
        }

        @Override
        protected boolean shouldReplaceOnDuplicate(CodeUnit existing, CodeUnit candidate) {
            // Force replacement path ONLY when logical identity (including signature) matches.
            // This tests that TreeSitterAnalyzer.addTopLevelCodeUnit correctly isolates
            // the replacement to just the matching overload.
            return existing.isFunction()
                    && candidate.isFunction()
                    && Objects.equals(existing.fqName(), candidate.fqName())
                    && Objects.equals(existing.signature(), candidate.signature());
        }
    }

    @Test
    void testReplacementOfSingleOverloadPreservesOtherOverloads() throws IOException {
        // C.java contains:
        // 1. void m(int x) - initial definition
        // 2. void m(String s) - distinct overload
        // 3. void m(int x) - duplicate of #1, triggers replacement logic in ReplacingJavaAnalyzer
        String javaSource =
                """
                class C {
                  void m(int x) { int a = 1; }
                  void m(String s) { int b = 2; }
                  void m(int x) { int c = 3; }
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(javaSource, "C.java").build()) {
            ReplacingJavaAnalyzer analyzer = new ReplacingJavaAnalyzer(project);

            // 1. Assert getDefinitions("C.m") returns exactly 2 entries (one for (int), one for (String))
            SequencedSet<CodeUnit> definitions = analyzer.getDefinitions("C.m");
            assertEquals(2, definitions.size(), "Should have exactly 2 overloads for C.m");

            List<String> signatures = definitions.stream()
                    .map(CodeUnit::signature)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            assertEquals(List.of("(String)", "(int)"), signatures);

            for (CodeUnit cu : definitions) {
                assertEquals("C.m", cu.fqName());
            }

            // 2. Assert searchDefinitions returns both
            assertTrue(
                    analyzer.searchDefinitions("C.m", false).stream().anyMatch(cu -> "(int)".equals(cu.signature())));
            assertTrue(analyzer.searchDefinitions("C.m", false).stream()
                    .anyMatch(cu -> "(String)".equals(cu.signature())));

            // 3. Assert structural correctness (children of class C)
            CodeUnit classCu = analyzer.getDefinitions("C").stream()
                    .filter(CodeUnit::isClass)
                    .findFirst()
                    .orElseThrow();

            List<CodeUnit> children = analyzer.getDirectChildren(classCu);
            List<String> childSigs = children.stream()
                    .filter(CodeUnit::isFunction)
                    .map(CodeUnit::signature)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();

            assertEquals(
                    List.of("(String)", "(int)"), childSigs, "Class children should include both preserved overloads");
        }
    }
}
