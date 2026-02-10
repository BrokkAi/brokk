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

public class JavaScriptCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFunctionFiltersUnusedEs6Imports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                export class Foo {}
                """, "foo.js")
                .addFileContents("""
                export class Bar {}
                """, "bar.js");

        try (var project = builder.addFileContents(
                        """
                import { Foo } from './foo';
                import { Bar } from './bar';

                export function useFoo() {
                    const f = new Foo();
                }
                """,
                        "consumer.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function =
                    analyzer.getDefinitions("useFoo").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("import { Foo } from './foo';"), "Should include import for used type Foo");
            assertFalse(text.contains("import { Bar } from './bar';"), "Unused import Bar should be filtered out");
            assertTrue(text.contains("function useFoo"), "Should contain the function definition");
        }
    }

    @Test
    void testFunctionFiltersUnusedDefaultImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                export default class Foo {}
                """, "foo.js")
                .addFileContents("""
                export default class Bar {}
                """, "bar.js");

        try (var project = builder.addFileContents(
                        """
                import Foo from './foo';
                import Bar from './bar';

                export function work() {
                    const f = new Foo();
                }
                """,
                        "app.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function = analyzer.getDefinitions("work").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("import Foo from './foo';"), "Should include import for used Foo");
            assertFalse(text.contains("import Bar from './bar';"), "Unused import Bar should be filtered out");
            assertTrue(text.contains("function work"), "Should contain the function code");
        }
    }

    @Test
    void testClassIncludesMultipleRelevantImports() throws IOException {
        var builder = InlineTestProjectCreator.code("""
                export class A {}
                """, "a.js")
                .addFileContents("""
                export class B {}
                """, "b.js")
                .addFileContents("""
                export class C {}
                """, "c.js");

        try (var project = builder.addFileContents(
                        """
                import { A } from './a';
                import { B } from './b';
                import { C } from './c';

                class MultiUser {
                    constructor() {
                        this.a = new A();
                    }
                    doWork(b) {
                        return new B();
                    }
                }
                """,
                        "multi.js")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var cls = analyzer.getDefinitions("MultiUser").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, cls);
            String text = fragment.text().join();

            assertTrue(text.contains("import { A } from './a';"), "Should include import for used type A");
            assertTrue(text.contains("import { B } from './b';"), "Should include import for used type B");
            assertFalse(text.contains("import { C } from './c';"), "Unused import C should be filtered out");
            assertTrue(text.contains("class MultiUser"), "Should contain the class definition");
        }
    }
}
