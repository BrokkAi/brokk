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

public class PythonCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFunctionFiltersUnusedImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                class Foo:
                    pass
                """, "pkg/foo.py")
                .addFileContents(
                        """
                class Bar:
                    pass
                """, "pkg/bar.py");

        try (var project = builder.addFileContents(
                        """
                from pkg.foo import Foo
                from pkg.bar import Bar

                def use_foo(val: Foo):
                    print(val)
                """,
                        "main.py")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function =
                    analyzer.getDefinitions("main.use_foo").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("from pkg.foo import Foo"), "Should include import for used type Foo");
            assertFalse(text.contains("from pkg.bar import Bar"), "Unused import Bar should be filtered out");
            assertTrue(text.contains("def use_foo"), "Should contain the function definition");
        }
    }

    @Test
    void testClassIncludesRelevantModuleImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                class UtilA:
                    pass
                """, "lib/util_a.py")
                .addFileContents(
                        """
                class UtilB:
                    pass
                """, "lib/util_b.py");

        try (var project = builder.addFileContents(
                        """
                from lib import util_a
                from lib import util_b

                class Processor:
                    def process(self):
                        print(util_a.UtilA())
                """,
                        "processor.py")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var cls = analyzer.getDefinitions("processor.Processor").stream()
                    .findFirst()
                    .orElseThrow();
            var fragment = new CodeFragment(contextManager, cls);
            String text = fragment.text().join();

            assertTrue(text.contains("from lib import util_a"), "Should include relevant module import");
            assertFalse(text.contains("from lib import util_b"), "Should exclude unused module import");
            assertTrue(text.contains("class Processor:"), "Should contain the class definition");
        }
    }

    @Test
    void testFunctionWithMultipleRelevantImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                class A: pass
                """, "models/a.py")
                .addFileContents("""
                class B: pass
                """, "models/b.py")
                .addFileContents("""
                class C: pass
                """, "models/c.py");

        try (var project = builder.addFileContents(
                        """
                from models.a import A
                from models.b import B
                from models.c import C

                def multi_use(a: A, b: B):
                    return None
                """,
                        "app.py")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function = analyzer.getDefinitions("app.multi_use").stream()
                    .findFirst()
                    .orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("from models.a import A"), "Should include used import A");
            assertTrue(text.contains("from models.b import B"), "Should include used import B");
            assertFalse(text.contains("from models.c import C"), "Should filter unused import C");
        }
    }
}
