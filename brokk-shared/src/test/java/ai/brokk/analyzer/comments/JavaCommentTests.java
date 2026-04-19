package ai.brokk.analyzer.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class JavaCommentTests {

    @Test
    void classWithJavadocOnly() {
        String code =
                """
                package com.example;
                /** Class level javadoc. */
                public class OnlyDoc {
                }
                """;
        try (var testProject =
                InlineCoreProject.code(code, "com/example/OnlyDoc.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            Optional<CommentDensityStats> opt = analyzer.commentDensity(classUnit(analyzer, "com.example.OnlyDoc"));
            assertTrue(opt.isPresent());
            CommentDensityStats s = opt.get();
            assertEquals(1, s.headerCommentLines());
            assertEquals(0, s.inlineCommentLines());
            assertTrue(s.spanLines() >= 1);
            assertEquals(s.headerCommentLines(), s.rolledUpHeaderCommentLines());
        }
    }

    @Test
    void methodWithJavadocAndInlineLineComment() {
        String code =
                """
                package com.example;
                public class Foo {
                    /** Method javadoc. */
                    public void bar() {
                        // end of line note
                        int x = 1;
                    }
                }
                """;
        try (var testProject =
                InlineCoreProject.code(code, "com/example/Foo.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            Optional<CommentDensityStats> opt = analyzer.commentDensity(methodUnit(analyzer, "com.example.Foo.bar"));
            assertTrue(opt.isPresent());
            CommentDensityStats s = opt.get();
            assertEquals(1, s.headerCommentLines());
            assertEquals(1, s.inlineCommentLines());
        }
    }

    @Test
    void twoMethodsDifferentDensities() {
        String code =
                """
                package com.example;
                public class Two {
                    /** dense header */
                    public void dense() {
                        // one
                        // two
                        int a = 1;
                    }

                    /** sparse header */
                    public void sparse() {
                        int b = 2;
                    }
                }
                """;
        try (var testProject =
                InlineCoreProject.code(code, "com/example/Two.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            CommentDensityStats dense = analyzer.commentDensity(methodUnit(analyzer, "com.example.Two.dense"))
                    .orElseThrow();
            CommentDensityStats sparse = analyzer.commentDensity(methodUnit(analyzer, "com.example.Two.sparse"))
                    .orElseThrow();
            assertEquals(1, dense.headerCommentLines());
            assertEquals(2, dense.inlineCommentLines());
            assertEquals(1, sparse.headerCommentLines());
            assertEquals(0, sparse.inlineCommentLines());
        }
    }

    @Test
    void nestedClassRollsUpChildCommentLines() {
        String code =
                """
                package com.example;
                /** outer */
                public class Outer {
                    /** inner */
                    public static class Inner {
                        void m() {}
                    }
                }
                """;
        try (var testProject =
                InlineCoreProject.code(code, "com/example/Outer.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            CommentDensityStats outer = analyzer.commentDensity(classUnit(analyzer, "com.example.Outer"))
                    .orElseThrow();
            CodeUnit innerCu = classUnit(analyzer, "com.example.Outer$Inner");
            CommentDensityStats inner = analyzer.commentDensity(innerCu).orElseThrow();
            assertEquals(1, inner.headerCommentLines());
            assertTrue(outer.rolledUpHeaderCommentLines() >= outer.headerCommentLines());
            assertTrue(outer.rolledUpHeaderCommentLines() >= inner.rolledUpHeaderCommentLines());
            var file = testProject
                    .getFileByRelPath(Path.of("com/example/Outer.java"))
                    .orElseThrow();
            List<CommentDensityStats> rows = analyzer.commentDensityByTopLevel(file);
            assertEquals(analyzer.getTopLevelDeclarations(file).size(), rows.size());
            CommentDensityStats outerRow = rows.stream()
                    .filter(r -> r.fqName().equals(outer.fqName()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(outerRow.rolledUpHeaderCommentLines() >= 2);
        }
    }

    @Test
    void commentDensityByTopLevelOneRowPerTopLevelDeclaration() {
        String code =
                """
                package com.example;
                public class A {}
                class B {}
                """;
        try (var testProject =
                InlineCoreProject.code(code, "com/example/MultiTop.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            var file = testProject
                    .getFileByRelPath(Path.of("com/example/MultiTop.java"))
                    .orElseThrow();
            List<CommentDensityStats> rows = analyzer.commentDensityByTopLevel(file);
            assertEquals(analyzer.getTopLevelDeclarations(file).size(), rows.size());
        }
    }

    private static CodeUnit classUnit(IAnalyzer analyzer, String fqcn) {
        return analyzer.getDefinitions(fqcn).stream()
                .filter(CodeUnit::isClass)
                .findFirst()
                .orElseThrow(() -> new AssertionError("class not found: " + fqcn));
    }

    private static CodeUnit methodUnit(IAnalyzer analyzer, String fqMethod) {
        return analyzer.getDefinitions(fqMethod).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .orElseThrow(() -> new AssertionError("method not found: " + fqMethod));
    }
}
