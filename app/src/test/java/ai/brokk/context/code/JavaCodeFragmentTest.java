package ai.brokk.context.code;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.ContextFragments.CodeFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JavaCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testMethodFiltersUnusedImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                package pkg;
                public class Foo {}
                """,
                        "pkg/Foo.java")
                .addFileContents(
                        """
                package pkg;
                public class Bar {}
                """,
                        "pkg/Bar.java");

        try (var project = builder.addFileContents(
                        """
                package consumer;
                import pkg.Foo;
                import pkg.Bar;

                public class Consumer {
                    public void useFoo(Foo foo) {
                        // only uses Foo
                    }
                }
                """,
                        "consumer/Consumer.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var method = analyzer.getDefinitions("consumer.Consumer.useFoo").stream()
                    .findFirst()
                    .orElseThrow();
            var fragment = new CodeFragment(contextManager, method);
            String text = fragment.text().join();

            assertTrue(text.contains("import pkg.Foo;"), "Should include import for used type Foo");
            assertFalse(text.contains("import pkg.Bar;"), "Unused import pkg.Bar should be filtered out");
            assertTrue(text.contains("useFoo"), "Should contain the method name");
            assertTrue(text.contains("Foo foo"), "Should contain the parameter using Foo type");
        }
    }

    @Test
    void testClassIncludesMultipleRelevantImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                package pkg;
                public class A {}
                """,
                        "pkg/A.java")
                .addFileContents(
                        """
                package pkg;
                public class B {}
                """,
                        "pkg/B.java")
                .addFileContents(
                        """
                package pkg;
                public class C {}
                """,
                        "pkg/C.java");

        try (var project = builder.addFileContents(
                        """
                package consumer;
                import pkg.A;
                import pkg.B;
                import pkg.C;

                public class MultiUser {
                    private A a;
                    public void doWork(B b) {}
                }
                """,
                        "consumer/MultiUser.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var cls = analyzer.getDefinitions("consumer.MultiUser").stream()
                    .findFirst()
                    .orElseThrow();
            var fragment = new CodeFragment(contextManager, cls);
            String text = fragment.text().join();

            assertTrue(text.contains("import pkg.A;"), "Should include import for used type A");
            assertTrue(text.contains("import pkg.B;"), "Should include import for used type B");
            assertFalse(text.contains("import pkg.C;"), "Unused import pkg.C should be filtered out");
            assertTrue(text.contains("private A a"), "Should contain field using type A");
            assertTrue(text.contains("doWork(B b)"), "Should contain method using type B");
        }
    }

    @Test
    void testInnerClassFiltersRelevantImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                package pkg;
                public class TypeA {}
                """,
                        "pkg/TypeA.java")
                .addFileContents(
                        """
                package pkg;
                public class TypeB {}
                """,
                        "pkg/TypeB.java");

        try (var project = builder.addFileContents(
                        """
                package consumer;
                import pkg.TypeA;
                import pkg.TypeB;

                public class Outer {
                    public static class Inner {
                        public void method(TypeB b) {}
                    }

                    public void outerMethod(TypeA a) {}
                }
                """,
                        "consumer/Outer.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var innerCls = analyzer.getDefinitions("consumer.Outer.Inner").stream()
                    .findFirst()
                    .orElseThrow();
            var fragment = new CodeFragment(contextManager, innerCls);
            String text = fragment.text().join();

            assertTrue(text.contains("import pkg.TypeB;"), "Inner class should include import for TypeB it uses");
            assertFalse(
                    text.contains("import pkg.TypeA;"),
                    "Inner class fragment should not include imports used only by outer class");
            assertTrue(text.contains("class Inner"), "Should contain the inner class");
            assertTrue(text.contains("method(TypeB b)"), "Should contain the method using TypeB");
        }
    }
}
