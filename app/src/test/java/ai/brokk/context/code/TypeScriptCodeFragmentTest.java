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

public class TypeScriptCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFunctionFiltersUnusedTsImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                export interface Foo {
                    id: string;
                }
                """,
                        "foo.ts")
                .addFileContents(
                        """
                export interface Bar {
                    name: string;
                }
                """,
                        "bar.ts");

        try (var project = builder.addFileContents(
                        """
                import { Foo } from './foo';
                import { Bar } from './bar';

                export function processFoo(f: Foo) {
                    console.log(f.id);
                }
                """,
                        "app.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function =
                    analyzer.getDefinitions("processFoo").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("import { Foo } from './foo';"), "Should include import for used type Foo");
            assertFalse(text.contains("import { Bar } from './bar';"), "Unused import Bar should be filtered out");
            assertTrue(text.contains("function processFoo"), "Should contain the function definition");
        }
    }

    @Test
    void testHandlesTypeOnlyImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                export interface User {
                    username: string;
                }
                """,
                        "types.ts")
                .addFileContents("""
                export class Utils {}
                """, "utils.ts");

        try (var project = builder.addFileContents(
                        """
                import type { User } from './types';
                import { Utils } from './utils';

                export function greet(u: User) {
                    return "Hello " + u.username;
                }
                """,
                        "service.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var function = analyzer.getDefinitions("greet").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(
                    text.contains("import type { User } from './types';"), "Should include type-only import for User");
            assertFalse(
                    text.contains("import { Utils } from './utils';"), "Unused import Utils should be filtered out");
        }
    }

    @Test
    void testClassIncludesMultipleRelevantImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                export class Engine {}
                """, "engine.ts")
                .addFileContents("""
                export interface Config {}
                """, "config.ts")
                .addFileContents("""
                export class Unused {}
                """, "unused.ts");

        try (var project = builder.addFileContents(
                        """
                import { Engine } from './engine';
                import { Config } from './config';
                import { Unused } from './unused';

                class Controller {
                    private engine: Engine;
                    constructor(cfg: Config) {
                        this.engine = new Engine();
                    }
                }
                """,
                        "controller.ts")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);

            var cls = analyzer.getDefinitions("Controller").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, cls);
            String text = fragment.text().join();

            assertTrue(
                    text.contains("import { Engine } from './engine';"), "Should include import for used class Engine");
            assertTrue(
                    text.contains("import { Config } from './config';"),
                    "Should include import for used interface Config");
            assertFalse(
                    text.contains("import { Unused } from './unused';"), "Unused import Unused should be filtered out");
            assertTrue(text.contains("class Controller"), "Should contain the class definition");
        }
    }
}
