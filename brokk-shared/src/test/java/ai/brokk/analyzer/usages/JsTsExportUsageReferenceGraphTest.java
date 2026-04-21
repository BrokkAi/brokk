package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JsTsExportUsageReferenceGraphTest {

    @Test
    public void namedImportAlias_resolvesToExportedSymbol() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import { foo as bar } from "./a";
                bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);

            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            var hit = result.hits().iterator().next();
            assertTrue(hit.file().toString().endsWith("b.ts"));
            assertEquals("foo", hit.resolved().identifier());
        }
    }

    @Test
    public void namespaceImport_resolvesMemberReference() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import * as NS from "./a";
                NS.foo();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void reexportChain_isFollowed() throws Exception {
        String a = """
                export const foo = 1;
                """;
        String index = """
                export { foo } from "./a";
                """;
        String b = """
                import { foo } from "./index";
                foo;
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile indexFile = projectFile(project.getAllFiles(), "index.ts");
            assertTrue(analyzer.exportIndexOf(indexFile).exportsByName().containsKey("foo"));

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("b.ts"));
        }
    }

    @Test
    public void localShadowing_doesNotCountAsUsage() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b =
                """
                import { foo as bar } from "./a";
                function f() {
                  const bar = 1;
                  bar;
                }
                bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            // Only the call to imported bar() should count.
            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void classInheritance_polymorphicClassUsageCounts() throws Exception {
        String a =
                """
                export class Base {}
                export class Child extends Base {}
                """;
        String b = """
                import { Child } from "./a";
                new Child();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Base", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
        }
    }

    private static ProjectFile projectFile(Set<ProjectFile> files, String fileName) {
        return files.stream()
                .filter(pf -> pf.toString().endsWith(fileName))
                .findFirst()
                .orElseThrow();
    }
}
